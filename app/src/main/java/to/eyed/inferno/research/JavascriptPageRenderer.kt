package to.eyed.inferno.research

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Temporary Android WebView fallback for JavaScript application shells.
 *
 * Each render gets a fresh AndroidX WebKit profile. Every HTTP(S) resource is fetched by the pinned native
 * OkHttp client through ResearchWebResourceLoader; Chromium itself never follows redirects or opens sockets.
 * A restrictive document CSP blocks cross-origin script connections, frames, forms and workers. The profile is
 * destroyed after DOM capture and then deleted. Devices without multi-profile support keep static extraction.
 */
internal class JavascriptPageRenderer(
    context: Context,
    private val client: OkHttpClient,
) {
    private val appContext = context.applicationContext
    private val renderMutex = Mutex()

    suspend fun render(url: String, originalHtml: String): String? = renderMutex.withLock {
        withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            // DNS validation and CSP injection are deliberately off the Android main thread.
            val requested = withContext(Dispatchers.IO) { ResearchUrlPolicy.safeUrl(url) }
                ?: return@withTimeoutOrNull null
            val renderHtml = withContext(Dispatchers.Default) {
                applyResearchContentPolicy(originalHtml, requested.toString())
            }

            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    val handler = Handler(Looper.getMainLooper())
                    val resourceLoader = ResearchWebResourceLoader(client)
                    val activeCalls = ConcurrentHashMap.newKeySet<Call>()
                    var webView: WebView? = null
                    var profileStore: ProfileStore? = null
                    var profileName: String? = null
                    var finished = false
                    var samplingStarted = false
                    var lastSignature = ""
                    var stableSamples = 0
                    var pageStartedAt = 0L

                    fun dispose() {
                        handler.removeCallbacksAndMessages(null)
                        resourceLoader.close(activeCalls)

                        val active = webView
                        webView = null
                        active?.stopLoading()
                        active?.webChromeClient = null
                        active?.webViewClient = WebViewClient()
                        active?.destroy()

                        // The profile is unique to this render and is never reused by the application.
                        val store = profileStore
                        val name = profileName
                        profileStore = null
                        profileName = null
                        if (store != null && name != null) {
                            runCatching { store.deleteProfile(name) }
                        }
                    }

                    fun complete(html: String?) {
                        if (finished) return
                        finished = true
                        dispose()
                        if (continuation.isActive) continuation.resume(html)
                    }

                    continuation.invokeOnCancellation {
                        handler.post { complete(null) }
                    }

                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                        complete(null)
                        return@suspendCancellableCoroutine
                    }

                    val view = runCatching { WebView(appContext) }.getOrElse {
                        complete(null)
                        return@suspendCancellableCoroutine
                    }
                    webView = view

                    try {
                        val store = ProfileStore.getInstance()
                        val name = "inferno_research_" + UUID.randomUUID().toString().replace("-", "")
                        profileStore = store
                        profileName = name
                        // Must happen before touching WebView settings, clients, or loading a URL.
                        WebViewCompat.setProfile(view, name)
                    } catch (_: Exception) {
                        complete(null)
                        return@suspendCancellableCoroutine
                    }

                    view.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = false
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        allowFileAccess = false
                        allowContentAccess = false
                        allowFileAccessFromFileURLs = false
                        allowUniversalAccessFromFileURLs = false
                        loadsImagesAutomatically = false
                        blockNetworkImage = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        safeBrowsingEnabled = true
                        saveFormData = false
                        setGeolocationEnabled(false)
                        mediaPlaybackRequiresUserGesture = true
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)

                    lateinit var capture: () -> Unit
                    lateinit var poll: () -> Unit

                    capture = {
                        if (!finished) {
                            webView?.evaluateJavascript(
                                "(document.documentElement ? document.documentElement.outerHTML.slice(0,$MAX_RENDERED_HTML) : '')",
                            ) { raw ->
                                val html = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                                complete(html?.takeIf { it.length > MIN_HTML_LENGTH })
                            } ?: complete(null)
                        }
                    }

                    poll = {
                        if (!finished) {
                            val current = webView
                            if (current != null) current.evaluateJavascript(
                                "(function(){var b=document.body;return JSON.stringify({ready:document.readyState,length:b?(b.innerText||'').length:0,resources:performance.getEntriesByType('resource').length});})()",
                            ) { raw ->
                                val data = runCatching {
                                    JSONTokener(raw).nextValue() as? String
                                }.getOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }
                                if (data != null) {
                                    val signature = "${data.optString("ready")}:${data.optInt("length")}:${data.optInt("resources")}"
                                    if (signature == lastSignature) stableSamples++ else stableSamples = 0
                                    lastSignature = signature
                                }

                                val elapsed = SystemClock.elapsedRealtime() - pageStartedAt
                                if ((elapsed >= MIN_SETTLE_MS && stableSamples >= REQUIRED_STABLE_SAMPLES) ||
                                    elapsed >= MAX_SETTLE_MS
                                ) {
                                    capture()
                                } else {
                                    handler.postDelayed({ poll() }, SAMPLE_INTERVAL_MS)
                                }
                            }
                        }
                    }

                    view.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (!request.isForMainFrame) return false
                            val next = request.url.toString().toHttpUrlOrNull()
                            return next == null || !sameOrigin(next, requested)
                        }

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                            runCatching {
                                resourceLoader.intercept(request, requested, activeCalls)
                            }.getOrElse {
                                WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    502,
                                    "Resource request rejected",
                                    emptyMap(),
                                    java.io.ByteArrayInputStream(ByteArray(0)),
                                )
                            }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            val current = url.toHttpUrlOrNull()
                            if (current == null || !sameOrigin(current, requested)) complete(null)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (!samplingStarted) {
                                val current = url.toHttpUrlOrNull()
                                if (current != null && sameOrigin(current, requested)) {
                                    samplingStarted = true
                                    pageStartedAt = SystemClock.elapsedRealtime()
                                    handler.postDelayed({ poll() }, FIRST_SAMPLE_DELAY_MS)
                                }
                            }
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) complete(null)
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            errorResponse: WebResourceResponse,
                        ) {
                            if (request.isForMainFrame && errorResponse.statusCode >= 400) complete(null)
                        }

                        override fun onReceivedSslError(
                            view: WebView,
                            handler: SslErrorHandler,
                            error: android.net.http.SslError,
                        ) {
                            // All network is proxied through OkHttp; fail closed if Chromium reports any TLS error.
                            handler.cancel()
                            complete(null)
                        }

                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: android.webkit.RenderProcessGoneDetail,
                        ): Boolean {
                            complete(null)
                            return true
                        }
                    }

                    view.webChromeClient = object : WebChromeClient() {
                        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                            result.cancel()
                            return true
                        }

                        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                            result.cancel()
                            return true
                        }

                        override fun onJsPrompt(
                            view: WebView?,
                            url: String?,
                            message: String?,
                            defaultValue: String?,
                            result: JsPromptResult,
                        ): Boolean {
                            result.cancel()
                            return true
                        }

                        override fun onPermissionRequest(request: PermissionRequest) {
                            request.deny()
                        }
                    }

                    // The first document is the already-fetched static HTML, not an unpinned browser navigation.
                    view.loadDataWithBaseURL(requested.toString(), renderHtml, "text/html", "UTF-8", null)
                }
            }
        }
    }

    private fun applyResearchContentPolicy(html: String, baseUrl: String): String {
        val document = Jsoup.parse(html.take(MAX_RENDER_INPUT_HTML), baseUrl)
        document.head().prependElement("meta")
            .attr("http-equiv", "Content-Security-Policy")
            .attr("content", CONTENT_SECURITY_POLICY)
        return document.outerHtml()
    }

    private fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
        first.scheme == second.scheme && first.host == second.host && first.port == second.port

    companion object {
        private const val TOTAL_TIMEOUT_MS = 15_000L
        private const val FIRST_SAMPLE_DELAY_MS = 900L
        private const val SAMPLE_INTERVAL_MS = 400L
        private const val MIN_SETTLE_MS = 1_300L
        private const val MAX_SETTLE_MS = 4_200L
        private const val MAX_RENDER_INPUT_HTML = 2_000_000
        private const val MAX_RENDERED_HTML = 1_500_000
        private const val MIN_HTML_LENGTH = 250
        private const val REQUIRED_STABLE_SAMPLES = 2
        private const val CONTENT_SECURITY_POLICY =
            "default-src 'self'; " +
                "script-src 'self' 'unsafe-inline' 'unsafe-eval' https: http:; " +
                "style-src 'self' 'unsafe-inline' https: http:; " +
                "font-src 'self' data: https: http:; " +
                "img-src 'none'; media-src 'none'; " +
                "connect-src 'self'; frame-src 'none'; object-src 'none'; " +
                "worker-src 'none'; form-action 'none'; base-uri 'self'"
    }
}
