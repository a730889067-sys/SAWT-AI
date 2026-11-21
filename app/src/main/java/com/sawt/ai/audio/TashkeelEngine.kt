package com.sawt.ai.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.view.ViewGroup
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Offline Arabic diacritizer hosted in a private WebView.
 * Ichkil's 22 MB ONNX model is cached by WebView after the first online use.
 * All subsequent inference runs locally with no text sent to a server.
 */
class TashkeelEngine(
    context: Context,
    private val onStatus: (String) -> Unit = {}
) : Closeable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pageReady = CompletableDeferred<Unit>()
    private val nextRequestId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CancellableContinuation<String>>()
    @Volatile private var closed = false
    @Volatile private var lastProgressPercent = -1

    val webView: WebView = WebView(context)

    init {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.addJavascriptInterface(JsBridge(), BRIDGE_NAME)
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame && !pageReady.isCompleted) {
                    pageReady.completeExceptionally(
                        IOException("تعذر فتح محرك التشكيل المحلي داخل التطبيق.")
                    )
                }
            }
        }
        webView.loadUrl(ASSET_PAGE_URL)
    }

    suspend fun diacritize(text: String): String {
        if (closed) throw IOException("محرك التشكيل مغلق.")
        if (!TashkeelTextProcessor.containsArabic(text)) return text
        withTimeout(PAGE_READY_TIMEOUT_MS) { pageReady.await() }

        val result = StringBuilder(text.length + (text.length / 2))
        for (chunk in TashkeelTextProcessor.split(text)) {
            if (TashkeelTextProcessor.containsArabic(chunk)) {
                result.append(diacritizeChunk(chunk))
            } else {
                result.append(chunk)
            }
        }
        return result.toString()
    }

    /**
     * Releases the ONNX WebAssembly worker before audio synthesis to reduce
     * concurrent RAM pressure. The model stays in WebView's local cache.
     */
    fun releaseModelMemory() {
        if (closed) return
        webView.post {
            if (!closed) {
                webView.evaluateJavascript("window.sawtTashkeel?.dispose?.();", null)
            }
        }
    }

    private suspend fun diacritizeChunk(chunk: String): String =
        withTimeout(INFERENCE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val id = nextRequestId.getAndIncrement()
                pending[id] = continuation
                continuation.invokeOnCancellation { pending.remove(id) }

                val script = "window.sawtTashkeel.diacritize($id, ${JSONObject.quote(chunk)});"
                webView.post {
                    if (closed) {
                        pending.remove(id)
                        if (continuation.isActive) {
                            continuation.resumeWithException(IOException("محرك التشكيل مغلق."))
                        }
                    } else {
                        webView.evaluateJavascript(script, null)
                    }
                }
            }
        }

    inner class JsBridge {
        @JavascriptInterface
        fun onPageReady() {
            if (!pageReady.isCompleted) pageReady.complete(Unit)
        }

        @JavascriptInterface
        fun onWorkerReady() {
            reportStatus("التشكيل التلقائي جاهز ويعمل دون إنترنت.")
        }

        @JavascriptInterface
        fun onProgress(name: String, loaded: Double, total: Double) {
            val current = loaded.toLong().coerceAtLeast(0L)
            val maximum = total.toLong().coerceAtLeast(0L)
            val percent = if (maximum > 0L) {
                ((current * 100L) / maximum).toInt().coerceIn(0, 100)
            } else -1
            if (percent < 0 || percent == 100 || percent / 10 > lastProgressPercent / 10) {
                lastProgressPercent = percent
                val message = if (percent >= 0) {
                    "جارٍ تجهيز نموذج التشكيل العربي لأول مرة… $percent%"
                } else {
                    "جارٍ تنزيل نموذج التشكيل العربي لأول مرة…"
                }
                reportStatus(message)
            }
        }

        @JavascriptInterface
        fun onResult(id: Int, text: String) {
            val continuation = pending.remove(id) ?: return
            if (continuation.isActive) continuation.resume(text)
        }

        @JavascriptInterface
        fun onError(id: Int, message: String) {
            val error = IOException(message.ifBlank { "تعذر تشكيل النص العربي." })
            if (id < 0) {
                reportStatus(error.message ?: "تعذر تشغيل التشكيل التلقائي.")
                return
            }
            val continuation = pending.remove(id) ?: return
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private fun reportStatus(message: String) {
        mainHandler.post {
            if (!closed) onStatus(message)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.values.forEach { continuation ->
            if (continuation.isActive) continuation.cancel()
        }
        pending.clear()
        mainHandler.post {
            try {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.stopLoading()
                webView.removeJavascriptInterface(BRIDGE_NAME)
                webView.loadUrl("about:blank")
                webView.destroy()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val BRIDGE_NAME = "SAWTTashkeelNative"
        private const val ASSET_PAGE_URL =
            "https://appassets.androidplatform.net/assets/voice_engine/tashkeel.html"
        private const val PAGE_READY_TIMEOUT_MS = 20_000L
        private const val INFERENCE_TIMEOUT_MS = 180_000L
    }
}
