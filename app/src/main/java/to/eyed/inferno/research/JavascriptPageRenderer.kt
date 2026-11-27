package to.eyed.inferno.research

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JsResult
import android.webkit.JsPromptResult
import android.net.http.SslError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/**
 * A temporary, invisible Android WebView used only when static extraction detects an app shell.
 * It executes public-page JavaScript locally, captures the final DOM and destroys the view. It is not a browser
 * session: cookies, form data, popups, navigation away from the requested host and non-GET requests are disabled.
 */
internal class JavascriptPageRenderer(context: Context) {
    private val appContext = context.applicationContext

    suspend fun render(url: String): String? = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val handler = Handler(Looper.getMainLooper())
                var webView: WebView? = null
                var finished = false
                var samplingStarted = false
                var lastSignature = ""
                var stableSamples = 0
                var pageStartedAt = 0L

                fun dispose() {
                    handler.removeCallbacksAndMessages(null)
                    webView?.stopLoading()
                    webView?.webChromeClient = null
                    webView?.webViewClient = WebViewClient()
                    webView?.destroy()
                    webView = null
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

                val requested = ResearchUrlPolicy.safeUrl(url)
                if (requested == null) {
                    complete(null)
                    return@suspendCancellableCoroutine
                }

                val view = runCatching { WebView(appContext) }.getOrElse {
                    complete(null)
                    return@suspendCancellableCoroutine
                }
                webView = view
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
                }
                CookieManager.getInstance().setAcceptCookie(false)
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)

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
                        val current = webView ?: return@let
                        current.evaluateJavascript(
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
                            if ((elapsed >= MIN_SETTLE_MS && stableSamples >= 2) || elapsed >= MAX_SETTLE_MS) {
                                capture()
                            } else {
                                handler.postDelayed(poll, SAMPLE_INTERVAL_MS)
                            }
                        }
                    }
                }

                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return false
                        val next = ResearchUrlPolicy.safeUrl(request.url.toString())
                        return next == null || next.host != requested.host
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (!request.method.equals("GET", true) ||
                            !ResearchUrlPolicy.isSafePublicUrl(request.url.toString())) return deniedResponse()
                        if (request.isForMainFrame && request.url.host != requested.host) return deniedResponse()
                        return null
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        if (ResearchUrlPolicy.safeUrl(url) == null) complete(null)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        if (!samplingStarted && ResearchUrlPolicy.safeUrl(url)?.host == requested.host) {
                            samplingStarted = true
                            pageStartedAt = SystemClock.elapsedRealtime()
                            handler.postDelayed(poll, FIRST_SAMPLE_DELAY_MS)
                        }
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) complete(null)
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        if (request.isForMainFrame && errorResponse.statusCode >= 400) complete(null)
                    }

                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        handler.cancel()
                        complete(null)
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
                }
                view.loadUrl(requested.toString())
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
    }
}
