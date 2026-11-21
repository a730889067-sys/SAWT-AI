package com.sawt.ai.audio

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException

/** Local Nabra-7M MSA voice runtime hosted in an isolated app-assets WebView. */
@SuppressLint("SetJavaScriptEnabled")
class NabraTtsEngine(
    context: Context,
    private val onStatus: (String) -> Unit = {}
) : Closeable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pageReady = CompletableDeferred<Unit>()
    private val operationMutex = Mutex()
    @Volatile private var closed = false
    @Volatile private var rendererGone = false
    @Volatile private var pendingAudio: CompletableDeferred<File>? = null
    @Volatile private var pendingReady: CompletableDeferred<Unit>? = null
    @Volatile private var pendingRelease: CompletableDeferred<Unit>? = null
    @Volatile private var currentProgress: ((String, Long, Long) -> Unit)? = null

    private val bridge = NabraLocalBridge(appContext) { event -> handleEvent(event) }
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
        webView.addJavascriptInterface(bridge, BRIDGE_NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean =
                request.url.scheme != "https" ||
                    request.url.host != "appassets.androidplatform.net"

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                rendererGone = true
                val error = IOException("تعطل محرّك الصوت الإضافي. أعد فتح التطبيق ثم حاول من جديد.")
                if (!pageReady.isCompleted) pageReady.completeExceptionally(error)
                pendingAudio?.completeExceptionally(error)
                pendingAudio = null
                pendingReady?.completeExceptionally(error)
                pendingReady = null
                reportStatus(error.message ?: "تعذر تشغيل محرك الصوت الإضافي.")
                return true
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame && !pageReady.isCompleted) {
                    pageReady.completeExceptionally(
                        IOException("تعذر فتح محرك الصوت الإضافي المحلي.")
                    )
                }
            }
        }
        webView.loadUrl(ASSET_PAGE_URL)
    }

    suspend fun prepare(
        pack: VoicePack,
        onProgress: (name: String, loaded: Long, total: Long) -> Unit = { _, _, _ -> }
    ) {
        operationMutex.withLock {
            ensurePageReady()
            val ready = CompletableDeferred<Unit>()
            pendingReady = ready
            currentProgress = onProgress
            try {
                evaluate("window.sawtTts?.load?.(${optionsJson(pack)});")
                withTimeout(LOAD_TIMEOUT_MS) { ready.await() }
                reportStatus("اكتمل تنزيل وتجهيز ${pack.name}.")
            } catch (error: Exception) {
                throw IOException("تعذر تنزيل أو تجهيز الصوت ${pack.name}: ${error.message}", error)
            } finally {
                if (pendingReady === ready) pendingReady = null
                currentProgress = null
            }
        }
    }

    suspend fun releaseRuntime() {
        operationMutex.withLock {
            ensurePageReady()
            val released = CompletableDeferred<Unit>()
            pendingRelease = released
            try {
                evaluate("window.sawtTts?.releaseRuntime?.();")
                withTimeout(RELEASE_TIMEOUT_MS) { released.await() }
            } catch (error: Exception) {
                throw IOException("تعذر تحرير ذاكرة الصوت الإضافي: ${error.message}", error)
            } finally {
                if (pendingRelease === released) pendingRelease = null
            }
        }
    }

    suspend fun synthesizeToFile(
        text: String,
        pack: VoicePack,
        speed: Float,
        onProgress: (name: String, loaded: Long, total: Long) -> Unit = { _, _, _ -> }
    ): File {
        require(text.isNotBlank()) { "اكتب النص أولًا." }
        require(text.length <= MAX_TEXT_CHARACTERS) { "قسّم النص إلى فقرات أقصر." }
        operationMutex.lock()
        try {
            ensurePageReady()
            val result = CompletableDeferred<File>()
            pendingAudio = result
            currentProgress = onProgress
            try {
                val script =
                    "window.sawtTts?.generate?.(${JSONObject.quote(text)}, " +
                    "${speed.coerceIn(0.8f, 1.2f)}, ${optionsJson(pack)});"
                evaluate(script)
                return withTimeout(GENERATION_TIMEOUT_MS) { result.await() }
            } catch (error: Exception) {
                throw IOException("تعذر إنشاء الصوت الفصيح الإضافي: ${error.message}", error)
            } finally {
                if (pendingAudio === result) pendingAudio = null
                currentProgress = null
            }
        } finally {
            operationMutex.unlock()
        }
    }

    private suspend fun ensurePageReady() {
        if (closed || rendererGone) throw IOException("محرك الصوت الإضافي غير متاح.")
        withTimeout(PAGE_READY_TIMEOUT_MS) { pageReady.await() }
    }

    private fun optionsJson(pack: VoicePack): String = JSONObject().apply {
        put("repo", pack.repository)
        put("revision", pack.revision)
        put("dtype", pack.dtype)
        pack.modelUrl?.let { put("modelUrl", it) }
        pack.voiceUrl?.let { put("voiceUrl", it) }
        put("device", "wasm")
        put("worker", true)
        put("cache", true)
        put("normalize", false)
        put("numThreads", 1)
    }.toString()

    private fun evaluate(script: String) {
        if (closed || rendererGone) throw IOException("تم إغلاق محرك الصوت الإضافي.")
        webView.post {
            if (closed || rendererGone) {
                pendingAudio?.completeExceptionally(IOException("محرك الصوت غير متاح."))
                pendingReady?.completeExceptionally(IOException("محرك الصوت غير متاح."))
            } else {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    private fun handleEvent(event: NabraBridgeEvent) {
        when (event) {
            NabraBridgeEvent.PageReady -> if (!pageReady.isCompleted) pageReady.complete(Unit)
            is NabraBridgeEvent.Progress ->
                currentProgress?.invoke(event.name, event.loaded, event.total)
            is NabraBridgeEvent.EngineReady -> pendingReady?.complete(Unit)
            is NabraBridgeEvent.AudioReady -> pendingAudio?.complete(event.file)
            is NabraBridgeEvent.Error -> {
                val error = IOException(event.message)
                pendingAudio?.completeExceptionally(error)
                pendingReady?.completeExceptionally(error)
            }
            NabraBridgeEvent.ModelsRemoved -> reportStatus("حُذفت ملفات الصوت الإضافية.")
            NabraBridgeEvent.RuntimeReleased -> pendingRelease?.complete(Unit)
        }
    }

    private fun reportStatus(message: String) {
        mainHandler.post { if (!closed) onStatus(message) }
    }

    override fun close() {
        if (closed) return
        closed = true
        val error = IOException("أُغلق محرك الصوت الإضافي.")
        pendingAudio?.completeExceptionally(error)
        pendingAudio = null
        pendingReady?.completeExceptionally(error)
        pendingReady = null
        pendingRelease?.completeExceptionally(error)
        pendingRelease = null
        bridge.close()
        mainHandler.post {
            try { (webView.parent as? android.view.ViewGroup)?.removeView(webView) } catch (_: Exception) { }
            try { webView.stopLoading() } catch (_: Exception) { }
            try { webView.removeJavascriptInterface(BRIDGE_NAME) } catch (_: Exception) { }
            if (!rendererGone) try { webView.loadUrl("about:blank") } catch (_: Exception) { }
            try { webView.destroy() } catch (_: Exception) { }
        }
    }

    companion object {
        private const val BRIDGE_NAME = "SAWTBridge"
        private const val ASSET_PAGE_URL =
            "https://appassets.androidplatform.net/assets/voice_engine/index.html"
        private const val PAGE_READY_TIMEOUT_MS = 20_000L
        private const val LOAD_TIMEOUT_MS = 180_000L
        private const val GENERATION_TIMEOUT_MS = 180_000L
        private const val RELEASE_TIMEOUT_MS = 15_000L
        private const val MAX_TEXT_CHARACTERS = 2_000
    }
}
