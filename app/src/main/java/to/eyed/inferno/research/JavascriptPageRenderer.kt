package to.eyed.inferno.research

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Temporary Android WebView fallback for JavaScript application shells.
 *
 * Each render gets a fresh AndroidX WebKit profile. Cookies, DOM storage, IndexedDB and cache therefore
 * cannot leak into the app's default WebView profile or into a later research page. The profile is deleted
 * after its WebView is destroyed. If the installed WebView lacks multi-profile support, static extraction
 * remains available and this optional JavaScript fallback safely returns no result.
 */
internal class JavascriptPageRenderer(context: Context) {
    private val appContext = context.applicationContext
    private val renderMutex = Mutex()

    suspend fun render(url: String): String? = renderMutex.withLock {
        withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            // DNS resolution is blocking. Validate it on IO, never on Android's main thread.
            val requested = withContext(Dispatchers.IO) { ResearchUrlPolicy.safeUrl(url) }
                ?: return@withTimeoutOrNull null

            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    val handler = Handler(Looper.getMainLooper())
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
                        val active = webView
                        webView = null
                        active?.stopLoading()
                        active?.webChromeClient = null
                        active?.webViewClient = WebViewClient()
                        active?.destroy()

                        // Deleting a unique profile removes its cookies, DOM storage, databases and cache.
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
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)

                    fun deniedResponse() = WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        403,
                        "Blocked by Inferno research policy",
                        emptyMap(),
                        ByteArrayInputStream(ByteArray(0)),
                    )

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
                            return next == null ||
                                next.host != requested.host ||
                                (next.scheme != "https" && next.scheme != "http")
                        }

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            if (!request.method.equals("GET", true) ||
                                !ResearchUrlPolicy.isSafePublicUrl(request.url.toString())
                            ) return deniedResponse()

                            if (request.isForMainFrame && request.url.host != requested.host) {
                                return deniedResponse()
                            }
                            return null
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            val current = url.toHttpUrlOrNull()
                            if (current == null || current.host != requested.host ||
                                (current.scheme != "https" && current.scheme != "http")
                            ) {
                                complete(null)
                            }
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (!samplingStarted) {
                                val current = url.toHttpUrlOrNull()
                                if (current?.host == requested.host) {
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
                            // A TLS failure for the requested origin makes the captured document untrustworthy.
                            handler.cancel()
                            if (error.url.toHttpUrlOrNull()?.host == requested.host) complete(null)
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

                    view.loadUrl(requested.toString())
                }
            }
        }
    }

    companion object {
        private const val TOTAL_TIMEOUT_MS = 15_000L
        private const val FIRST_SAMPLE_DELAY_MS = 900L
        private const val SAMPLE_INTERVAL_MS = 400L
        private const val MIN_SETTLE_MS = 1_300L
        private const val MAX_SETTLE_MS = 4_200L
        private const val MAX_RENDERED_HTML = 1_500_000
        private const val MIN_HTML_LENGTH = 250
        private const val REQUIRED_STABLE_SAMPLES = 2
    }
}
