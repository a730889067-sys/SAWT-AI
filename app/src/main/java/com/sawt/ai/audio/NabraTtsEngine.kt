package com.sawt.ai.audio

import android.annotation.SuppressLint
import android.content.Context
import android.app.ActivityManager
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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Local Nabra-7M MSA voice runtime hosted in an isolated app-assets WebView. */
@SuppressLint("SetJavaScriptEnabled")
class NabraTtsEngine(
    context: Context,
    private val onStatus: (String) -> Unit = {}
) : Closeable {
    private val appContext = context.applicationContext
    private val modelsRoot = File(appContext.filesDir, "sawt-voice-packs")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pageReady = CompletableDeferred<Unit>()
    private val operationMutex = Mutex()
    @Volatile private var closed = false
    @Volatile private var rendererGone = false
    @Volatile private var pendingAudio: CompletableDeferred<File>? = null
    @Volatile private var pendingReady: CompletableDeferred<Unit>? = null
    @Volatile private var pendingRelease: CompletableDeferred<Unit>? = null
    @Volatile private var pendingRemoval: CompletableDeferred<Unit>? = null
    @Volatile private var currentProgress: ((String, Long, Long) -> Unit)? = null

    private val bridge = NabraLocalBridge(appContext) { event -> handleEvent(event) }
    val webView: WebView = WebView(context)

    init {
        if (!modelsRoot.exists()) modelsRoot.mkdirs()
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
            .addPathHandler("/models/", WebViewAssetLoader.InternalStoragePathHandler(appContext, modelsRoot))
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
            ensureMemoryBudget()
            withContext(Dispatchers.IO) { downloadPackModels(pack, onProgress) }
            ensurePageReady()
            val ready = CompletableDeferred<Unit>()
            pendingReady = ready
            currentProgress = onProgress
            try {
                evaluate("window.sawtTts?.load?.(${optionsJson(pack)});")
                withTimeout(LOAD_TIMEOUT_MS) { ready.await() }
                reportStatus("اكتمل تنزيل وتجهيز ${pack.name}.")
            } catch (error: OutOfMemoryError) {
                throw IOException("ذاكرة الهاتف غير كافية لحزمة Nabra الإضافية. استخدم صوت Supertonic الأساسي أو أغلق التطبيقات الأخرى.", error)
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
            ensureMemoryBudget()
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
            } catch (error: OutOfMemoryError) {
                try { bridge.cancelAudio() } catch (_: Exception) { }
                throw IOException("نفدت الذاكرة أثناء توليد Nabra. استخدم صوت Supertonic أو أغلق التطبيقات الأخرى.", error)
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


    private fun ensureMemoryBudget() {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: throw IOException("تعذر قراءة ذاكرة الهاتف؛ تم تعطيل حزمة Nabra التجريبية لتجنب الإغلاق.")
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        val minimumTotal = 3L * 1024L * 1024L * 1024L
        val minimumAvailable = 768L * 1024L * 1024L
        if (activityManager.isLowRamDevice || info.totalMem < minimumTotal || info.availMem < minimumAvailable) {
            throw IOException("حزمة Nabra تحتاج هاتفًا بذاكرة 3 GB على الأقل و768 MB متاحة تقريبًا. استخدم أحد أصوات Supertonic على هذا الجهاز.")
        }
    }

    private suspend fun ensurePageReady() {
        if (closed || rendererGone) throw IOException("محرك الصوت الإضافي غير متاح.")
        withTimeout(PAGE_READY_TIMEOUT_MS) { pageReady.await() }
    }

    private fun optionsJson(pack: VoicePack): String {
        val modelFile = if (pack.dtype == "fp32") "onnx/model.onnx" else "onnx/model_fp16.onnx"
        val localBase = "https://appassets.androidplatform.net/models/${pack.id}/"
        return JSONObject().apply {
            put("repo", pack.repository)
            put("revision", pack.revision)
            put("dtype", pack.dtype)
            put("modelUrl", localBase + modelFile)
            put("voiceUrl", localBase + "voices/af_msa.bin")
            put("localModels", true)
            put("device", "wasm")
            put("worker", true)
            put("cache", false)
            put("normalize", false)
            put("numThreads", 1)
        }.toString()
    }

    private fun downloadPackModels(
        pack: VoicePack,
        onProgress: (name: String, loaded: Long, total: Long) -> Unit
    ) {
        if (!pack.id.matches(Regex("[a-z0-9][a-z0-9-]{2,60}"))) {
            throw IOException("معرّف حزمة الصوت غير صالح.")
        }
        val revisionBase = "https://huggingface.co/${pack.repository}/resolve/${pack.revision}/"
        val modelRelativePath = if (pack.dtype == "fp32") "onnx/model.onnx" else "onnx/model_fp16.onnx"
        val modelFile = File(modelsRoot, "${pack.id}/$modelRelativePath")
        val voiceFile = File(modelsRoot, "${pack.id}/voices/af_msa.bin")
        val modelUrl = pack.modelUrl ?: revisionBase + modelRelativePath
        val voiceUrl = pack.voiceUrl ?: revisionBase + "voices/af_msa.bin"
        downloadResumable(modelUrl, modelFile, 1L * 1024L * 1024L, "ملف النموذج", onProgress)
        downloadResumable(voiceUrl, voiceFile, 64L * 1024L, "ملف بصمة الصوت", onProgress)
    }

    private fun downloadResumable(
        url: String,
        destination: File,
        minimumBytes: Long,
        label: String,
        onProgress: (name: String, loaded: Long, total: Long) -> Unit
    ) {
        if (destination.isFile && destination.length() >= minimumBytes) {
            onProgress(label, destination.length(), destination.length())
            return
        }
        val parent = destination.parentFile ?: throw IOException("مسار حزمة الصوت غير صالح.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("تعذر إنشاء مجلد حزمة الصوت.")
        val partFile = File(parent, destination.name + ".part")
        val totalFile = File(parent, destination.name + ".total")
        var knownTotal = runCatching { totalFile.readText().trim().toLong() }.getOrDefault(0L)
        var offset = partFile.length()
        if (knownTotal > 0L && offset > knownTotal) {
            partFile.delete()
            totalFile.delete()
            knownTotal = 0L
            offset = 0L
        }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 120_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "SAWT-AI-Android")
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            connection.connect()
            val status = connection.responseCode
            if (status == 416 && knownTotal > 0L && offset == knownTotal && offset >= minimumBytes) {
                if (destination.exists()) destination.delete()
                if (!partFile.renameTo(destination)) throw IOException("تعذر اعتماد ملف الصوت المكتمل.")
                totalFile.delete()
                onProgress(label, offset, knownTotal)
                return
            }
            if (status !in 200..299) throw IOException("تعذر تنزيل $label (HTTP $status).")
            val append = offset > 0L && status == HttpURLConnection.HTTP_PARTIAL
            if (offset > 0L && status == HttpURLConnection.HTTP_OK) {
                partFile.delete()
                totalFile.delete()
                offset = 0L
                knownTotal = 0L
            } else if (offset > 0L && !append) {
                throw IOException("لم يقبل الخادم استكمال $label (HTTP $status).")
            }
            if (append) {
                val range = connection.getHeaderField("Content-Range")
                if (range != null && !range.startsWith("bytes $offset-")) {
                    throw IOException("استجابة استكمال $label غير متطابقة؛ لم يتم دمج البيانات.")
                }
            }
            val responseLength = connection.contentLengthLong
            val responseTotal = connection.getHeaderField("Content-Range")
                ?.substringAfterLast("/")
                ?.toLongOrNull()
                ?: if (responseLength > 0L) offset + responseLength else 0L
            if (responseTotal > 0L) {
                if (knownTotal > 0L && knownTotal != responseTotal) {
                    partFile.delete()
                    totalFile.delete()
                    throw IOException("تغيّر حجم $label على المصدر؛ أُعيد ضبط الجزء الجزئي للحماية.")
                }
                knownTotal = responseTotal
                totalFile.writeText(knownTotal.toString())
            }
            var downloaded = offset
            onProgress(label, downloaded, knownTotal)
            BufferedInputStream(connection.inputStream).use { input ->
                val fileOutput = FileOutputStream(partFile, append)
                BufferedOutputStream(fileOutput).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(label, downloaded, knownTotal)
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            val finalSize = partFile.length()
            if (finalSize < minimumBytes || (knownTotal > 0L && finalSize != knownTotal)) {
                throw IOException("انقطع تنزيل $label؛ حُفظ الجزء الحالي ويمكن استكماله لاحقًا.")
            }
            if (destination.exists() && !destination.delete()) throw IOException("تعذر استبدال $label.")
            if (!partFile.renameTo(destination)) throw IOException("تعذر اعتماد $label بعد التنزيل.")
            totalFile.delete()
            onProgress(label, destination.length(), knownTotal.takeIf { it > 0L } ?: destination.length())
        } finally {
            connection.disconnect()
            // Keep .part and .total on connection loss so the next tap resumes the same file.
        }
    }

    fun packStorageBytes(packId: String): Long {
        if (!packId.matches(Regex("[a-z0-9][a-z0-9-]{2,60}"))) return 0L
        return File(modelsRoot, packId).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun packPendingBytes(packId: String): Long {
        if (!packId.matches(Regex("[a-z0-9][a-z0-9-]{2,60}"))) return 0L
        return File(modelsRoot, packId).walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".part") || it.name.endsWith(".total")) }
            .sumOf { it.length() }
    }

    suspend fun removePack(packId: String) {
        if (!packId.matches(Regex("[a-z0-9][a-z0-9-]{2,60}"))) {
            throw IOException("معرّف حزمة الصوت غير صالح.")
        }
        operationMutex.withLock {
            ensurePageReady()
            val removed = CompletableDeferred<Unit>()
            pendingRemoval = removed
            try {
                evaluate("window.sawtTts?.removeDownloadedModels?.();")
                withTimeout(RELEASE_TIMEOUT_MS) { removed.await() }
                withContext(Dispatchers.IO) {
                    File(modelsRoot, packId).deleteRecursively()
                }
            } finally {
                if (pendingRemoval === removed) pendingRemoval = null
            }
        }
    }

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
            NabraBridgeEvent.ModelsRemoved -> {
                reportStatus("حُذفت ملفات الصوت الإضافية.")
                pendingRemoval?.complete(Unit)
            }
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
        pendingRemoval?.completeExceptionally(error)
        pendingRemoval = null
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
