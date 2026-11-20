package com.sawt.ai.audio

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import com.sawt.ai.audio.DeviceTuning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Native, on-device Supertonic 3 engine.
 * Individual model files are downloaded to a staging directory and SHA-256 checked
 * before the complete model is moved into app-private storage.
 */
private data class ModelAsset(
    val fileName: String,
    val url: String,
    val expectedBytes: Long,
    val minimumBytes: Long,
    val sha256: String
)

class SupertonicTtsEngine(context: Context) {
    private val appContext = context.applicationContext
    private val modelsRoot = File(appContext.filesDir, "sawt-models")
    private val modelDirectory = File(modelsRoot, MODEL_DIRECTORY)
    private val operationMutex = processOperationMutex
    private val nativeEngineLock = Any()
    private val activeSynthesisCount = AtomicInteger(0)
    private val closeRequested = AtomicBoolean(false)

    @Volatile
    private var nativeTts: OfflineTts? = null

    @Volatile
    private var modelIntegrityVerified = false

    fun isModelInstalled(): Boolean = isInstalledDirectory(modelDirectory)

    fun needsOptimization(): Boolean = isModelInstalled() &&
        runCatching { File(modelDirectory, ".sawt-verified").readText().trim() == ARCHIVE_SHA256 }
            .getOrDefault(false)

    private fun isInstalledDirectory(directory: File): Boolean {
        return try {
            val marker = File(directory, ".sawt-verified")
            val markerValue = if (marker.isFile) marker.readText().trim() else ""
            (markerValue == ARCHIVE_SHA256 ||
                markerValue == OPTIMIZED_INSTALL_MARKER ||
                markerValue == DIRECT_INSTALL_MARKER) &&
                requiredModelFiles.all { (name, minimumBytes) ->
                    File(directory, name).let { it.isFile && it.length() >= minimumBytes }
                }
        } catch (_: Exception) {
            false
        }
    }

    private fun verifyInstalledModelIntegrity(onStage: (String) -> Unit) {
        if (modelIntegrityVerified) return
        val markerValue = File(modelDirectory, ".sawt-verified").readText().trim()
        val isLegacyArchiveModel = markerValue == ARCHIVE_SHA256
        MODEL_ASSETS.forEach { asset ->
            val file = File(modelDirectory, asset.fileName)
            if (!file.isFile || file.length() < asset.minimumBytes) {
                throw IOException("ملف الصوت ${asset.fileName} غير مكتمل. احذف حزمة الأصوات وأعد تنزيلها.")
            }

            // Older app versions installed the original vector estimator from a
            // SHA-verified archive. It intentionally has a different digest from
            // the mobile-optimized estimator downloaded by newer versions.
            if (isLegacyArchiveModel && asset.fileName == VECTOR_FILE) {
                onStage("تم التعرف على محرك الصوت الأصلي؛ يمكنك تحديث تحسين السرعة لاحقًا.")
                return@forEach
            }

            onStage("جارٍ التحقق من سلامة ${asset.fileName}…")
            val actualSha = calculateSha256(file)
            if (!actualSha.equals(asset.sha256, ignoreCase = true)) {
                throw IOException("فشل التحقق من سلامة ${asset.fileName}. احذف حزمة الأصوات وأعد تنزيلها.")
            }
        }
        modelIntegrityVerified = true
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun recoverInterruptedInstall() {
        modelIntegrityVerified = false
        val backupRoot = File(modelsRoot, ".supertonic-previous")
        if (backupRoot.exists()) {
            val primaryValid = isInstalledDirectory(modelDirectory)
            val backupValid = isInstalledDirectory(backupRoot)
            when {
                !primaryValid && backupValid -> {
                    modelDirectory.deleteRecursively()
                    if (!backupRoot.renameTo(modelDirectory)) {
                        throw IOException("تعذر استعادة حزمة الأصوات السابقة بعد توقف التثبيت.")
                    }
                }
                primaryValid -> backupRoot.deleteRecursively()
            }
        }

        val vectorFile = File(modelDirectory, VECTOR_FILE)
        val vectorBackup = File(modelDirectory, VECTOR_FILE + ".sawt-backup")
        if (!vectorFile.isFile && vectorBackup.isFile) {
            if (!vectorBackup.renameTo(vectorFile)) {
                throw IOException("تعذر استعادة ملف محرك الصوت السابق.")
            }
        } else if (vectorFile.isFile && vectorBackup.exists()) {
            vectorBackup.delete()
        }
    }

    suspend fun downloadAndInstall(
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        onStage: (String) -> Unit
    ) {
        operationMutex.lock()
        try {
            withContext(Dispatchers.IO) {
                recoverInterruptedInstall()
                if (isModelInstalled() && !needsOptimization()) {
                    onStage("حزمة الأصوات المحسّنة موجودة وسليمة بالفعل.")
                    return@withContext
                }
                if (needsOptimization()) {
                    onStage("جارٍ تنزيل تحسين السرعة للهواتف…")
                    upgradeInstalledModel(onProgress)
                    onStage("اكتمل تحسين محرك الصوت. يمكنك التحويل دون إنترنت.")
                    return@withContext
                }

                val availableBytes = StatFs(appContext.filesDir.absolutePath).availableBytes
                if (availableBytes < MINIMUM_FREE_SPACE_BYTES) {
                    throw IOException("يلزم توفر 350 ميجابايت تقريبًا من المساحة الفارغة لتنزيل الأصوات وتثبيتها.")
                }
                if (!modelsRoot.exists() && !modelsRoot.mkdirs()) {
                    throw IOException("تعذر تجهيز مساحة تثبيت الأصوات على الهاتف.")
                }

                File(appContext.cacheDir, "$MODEL_DIRECTORY.tar.bz2").delete()
                File(modelsRoot, ".supertonic-installing").deleteRecursively()
                File(modelsRoot, ".supertonic-previous").deleteRecursively()
                File(appContext.cacheDir, "vector_estimator.mobile.tmp").delete()

                val stagingRoot = File(modelsRoot, ".supertonic-installing")
                val backupRoot = File(modelsRoot, ".supertonic-previous")
                if (!stagingRoot.mkdirs()) {
                    throw IOException("تعذر تجهيز مساحة تثبيت الأصوات على الهاتف.")
                }

                try {
                    MODEL_ASSETS.forEachIndexed { index, asset ->
                        onStage("جارٍ تنزيل ${asset.fileName} (${index + 1}/${MODEL_ASSETS.size})…")
                        downloadVerifiedAsset(asset, File(stagingRoot, asset.fileName), onProgress)
                        onStage("تم تنزيل وفحص ${asset.fileName} (${index + 1}/${MODEL_ASSETS.size}).")
                    }

                    val missing = requiredModelFiles.firstOrNull { (name, minimumBytes) ->
                        val file = File(stagingRoot, name)
                        !file.isFile || file.length() < minimumBytes
                    }
                    if (missing != null) {
                        throw IOException("ملفات النموذج غير مكتملة (${missing.first}). أُوقف التثبيت لحماية التطبيق.")
                    }

                    File(stagingRoot, ".sawt-verified").writeText(DIRECT_INSTALL_MARKER)
                    onStage("تم تنزيل وفحص ملفات الأصوات السبعة. جارٍ اعتماد التثبيت…")
                    releaseNativeEngine()
                    backupRoot.deleteRecursively()
                    if (modelDirectory.exists() && !modelDirectory.renameTo(backupRoot)) {
                        throw IOException("تعذر تجهيز ملفات الصوت القديمة للاستبدال.")
                    }
                    if (!stagingRoot.renameTo(modelDirectory)) {
                        if (backupRoot.exists()) backupRoot.renameTo(modelDirectory)
                        throw IOException("تعذر اعتماد ملفات الصوت بعد التحقق منها.")
                    }
                    backupRoot.deleteRecursively()
                    modelIntegrityVerified = true
                    onStage("اكتمل تثبيت الأصوات العشرة. يمكنك الآن العمل دون إنترنت.")
                } catch (error: OutOfMemoryError) {
                    stagingRoot.deleteRecursively()
                    throw IOException("لم تكفِ ذاكرة الهاتف لإكمال تثبيت الأصوات. أغلق التطبيقات الأخرى وأعد المحاولة.", error)
                } catch (error: Exception) {
                    stagingRoot.deleteRecursively()
                    throw error
                }
            }
        } finally {
            operationMutex.unlock()
        }
    }

    suspend fun synthesizeToFile(
        text: String,
        language: String,
        speakerId: Int,
        speed: Float,
        onStage: (String) -> Unit = {},
        onProgress: (completedCharacters: Int, totalCharacters: Int) -> Unit = { _, _ -> }
    ): File {
        operationMutex.lock()
        var registered = false
        try {
            beginSynthesis()
            registered = true
            return withContext(Dispatchers.IO) {
                val spokenText = text.trim()
                if (spokenText.isEmpty()) throw IOException("اكتب النص أولًا.")
                if (spokenText.length > MAX_TEXT_CHARACTERS ||
                    spokenText.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES
                ) {
                    throw IOException("النص طويل. قسّمه إلى فقرات لا تتجاوز 2000 حرف.")
                }
                if (language !in setOf("ar", "en")) throw IOException("اختر العربية أو الإنجليزية.")
                if (speakerId !in 0..9) throw IOException("اختيار الصوت غير صالح.")
                if (!speed.isFinite()) throw IOException("سرعة القراءة غير صالحة.")
                if (!isModelInstalled()) throw IOException("نزّل حزمة الأصوات أولًا.")
                verifyInstalledModelIntegrity(onStage)

                val engine = try {
                    getOrCreateNativeEngine()
                } catch (error: OutOfMemoryError) {
                    releaseNativeEngine()
                    throw IOException("ذاكرة الهاتف غير كافية لتحميل محرك الصوت. أغلق التطبيقات الأخرى وأعد المحاولة.", error)
                } catch (error: LinkageError) {
                    releaseNativeEngine()
                    throw IOException("تعذر تحميل مكتبة محرك الصوت على هذا الجهاز.", error)
                } catch (error: Exception) {
                    releaseNativeEngine()
                    throw IOException("تعذر تجهيز محرك الصوت. أعد فتح التطبيق وحاول مجددًا.", error)
                }

                val config = GenerationConfig(
                    sid = speakerId,
                    speed = speed.coerceIn(0.8f, 1.2f),
                    numSteps = 4,
                    extra = mapOf("lang" to language)
                )
                val chunks = mutableListOf<String>()
                TextChunker.split(spokenText, MAX_CHUNK_CHARACTERS).forEach { chunk ->
                    when {
                        chunk.isBlank() && chunks.isNotEmpty() ->
                            chunks[chunks.lastIndex] = chunks.last() + chunk
                        chunk.isNotEmpty() -> chunks.add(chunk)
                    }
                }
                if (chunks.isEmpty()) throw IOException("لا يوجد نص صالح للتحويل.")

                onStage("تم تجهيز المحرك. سيُحوّل النص على دفعات صغيرة لتقليل استهلاك الذاكرة.")
                val output = try {
                    File.createTempFile("sawt-", ".wav", appContext.cacheDir)
                } catch (error: Exception) {
                    throw IOException("تعذر إنشاء ملف الصوت المؤقت. تحقق من مساحة التخزين.", error)
                }

                try {
                    Pcm16WavWriter(output).use { writer ->
                        val coroutineContext = currentCoroutineContext()
                        var completedCharacters = 0
                        for ((index, chunk) in chunks.withIndex()) {
                            coroutineContext.ensureActive()
                            onStage("جارٍ توليد الجزء ${index + 1} من ${chunks.size}…")
                            val audio = try {
                                engine.generateWithConfig(chunk, config)
                            } catch (error: OutOfMemoryError) {
                                releaseNativeEngine()
                                throw IOException("لم تكفِ ذاكرة الهاتف لإنشاء الجزء ${index + 1}. جرّب نصًا أقصر وأغلق التطبيقات الأخرى.", error)
                            } catch (error: LinkageError) {
                                releaseNativeEngine()
                                throw IOException("حدث خلل في مكتبة محرك الصوت أثناء التوليد.", error)
                            } catch (error: Exception) {
                                releaseNativeEngine()
                                throw IOException("تعذر توليد الجزء ${index + 1} من النص.", error)
                            }

                            if (audio.samples.isEmpty() || audio.sampleRate <= 0) {
                                releaseNativeEngine()
                                throw IOException("لم ينتج الجزء ${index + 1} ملفًا صوتيًا صالحًا.")
                            }
                            writer.append(audio.samples, audio.sampleRate)
                            completedCharacters += chunk.length
                            onProgress(completedCharacters, spokenText.length)
                            onStage("اكتمل الجزء ${index + 1} من ${chunks.size}.")
                        }
                        writer.finish()
                    }

                    if (!output.isFile || output.length() < WAV_MINIMUM_BYTES) {
                        throw IOException("لم يكتمل حفظ ملف الصوت.")
                    }
                    onStage("اكتمل إنشاء الصوت وحفظه.")
                    output
                } catch (error: OutOfMemoryError) {
                    output.delete()
                    releaseNativeEngine()
                    throw IOException("لم تكفِ ذاكرة الهاتف لإكمال ملف الصوت. أغلق التطبيقات الأخرى وجرّب فقرة أقصر.", error)
                } catch (error: Exception) {
                    output.delete()
                    throw error
                }
            }
        } finally {
            if (registered) endSynthesis()
            operationMutex.unlock()
        }
    }

    fun deleteDownloadedModels() {
        if (operationMutex.isLocked || activeSynthesisCount.get() > 0) {
            throw IllegalStateException("لا يمكن حذف الأصوات أثناء وجود عملية قيد التنفيذ.")
        }
        releaseNativeEngine()
        modelIntegrityVerified = false
        modelsRoot.deleteRecursively()
        File(appContext.cacheDir, "$MODEL_DIRECTORY.tar.bz2").delete()
        File(appContext.cacheDir, "vector_estimator.mobile.tmp").delete()
    }

    fun close() {
        closeRequested.set(true)
        if (activeSynthesisCount.get() == 0) releaseNativeEngine()
    }

    private fun beginSynthesis() {
        if (closeRequested.get()) throw CancellationException("تم إغلاق شاشة التحويل.")
        activeSynthesisCount.incrementAndGet()
        if (closeRequested.get()) {
            endSynthesis()
            throw CancellationException("تم إغلاق شاشة التحويل.")
        }
    }

    private fun endSynthesis() {
        val remaining = activeSynthesisCount.decrementAndGet()
        if (remaining <= 0 && closeRequested.get()) releaseNativeEngine()
    }

    private fun releaseNativeEngine() {
        synchronized(nativeEngineLock) {
            val current = nativeTts
            nativeTts = null
            try {
                current?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun getOrCreateNativeEngine(): OfflineTts = synchronized(nativeEngineLock) {
        nativeTts ?: createNativeEngine().also { nativeTts = it }
    }

    private fun recommendedNumThreads(): Int {
        val activityManager =
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        return DeviceTuning.recommendedCpuThreads(
            totalMemoryBytes = memoryInfo.totalMem,
            lowRamDevice = activityManager?.isLowRamDevice == true,
            availableProcessors = Runtime.getRuntime().availableProcessors()
        )
    }

    private fun createNativeEngine(directory: File = modelDirectory): OfflineTts {
        val config = getOfflineTtsConfig(
            modelDir = directory.absolutePath,
            isSupertonic = true,
            durationPredictor = "duration_predictor.int8.onnx",
            textEncoder = "text_encoder.int8.onnx",
            vectorEstimator = "vector_estimator.int8.onnx",
            supertonicVocoder = "vocoder.int8.onnx",
            ttsJson = "tts.json",
            unicodeIndexer = "unicode_indexer.bin",
            voiceStyle = "voice.bin",
            // Use one worker on low-memory devices (including typical 2 GB phones),
            // and at most two workers elsewhere to reduce peak native RAM use.
            numThreads = recommendedNumThreads()
        )
        return OfflineTts(config = config)
    }

    private fun upgradeInstalledModel(onProgress: (Long, Long) -> Unit) {
        val availableBytes = StatFs(appContext.filesDir.absolutePath).availableBytes
        if (availableBytes < MINIMUM_OPTIMIZATION_SPACE_BYTES) {
            throw IOException("يلزم توفر 160 ميجابايت على الأقل لتنزيل تحسين سرعة الصوت.")
        }
        val optimizedVector = File(appContext.cacheDir, "vector_estimator.mobile.tmp")
        try {
            downloadOptimizedVectorEstimator(optimizedVector, onProgress)
            releaseNativeEngine()
            replaceFileSafely(optimizedVector, File(modelDirectory, VECTOR_FILE))
            File(modelDirectory, ".sawt-verified").writeText(OPTIMIZED_INSTALL_MARKER)
            modelIntegrityVerified = false
        } catch (error: Exception) {
            optimizedVector.delete()
            throw error
        }
    }

    private fun downloadOptimizedVectorEstimator(
        destination: File,
        onProgress: (Long, Long) -> Unit
    ) {
        destination.delete()
        val connection = (URL(OPTIMIZED_VECTOR_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 90_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "SAWT-AI-Android")
        }
        try {
            connection.connect()
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("تعذر تنزيل تحسين الصوت (HTTP $status).")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: OPTIMIZED_VECTOR_SIZE_BYTES
            var downloaded = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(connection.inputStream).use { input ->
                val fileOutput = FileOutputStream(destination)
                BufferedOutputStream(fileOutput).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            if (downloaded < MIN_OPTIMIZED_VECTOR_SIZE_BYTES) {
                throw IOException("ملف تحسين الصوت غير مكتمل. أعد المحاولة.")
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!actualSha.equals(OPTIMIZED_VECTOR_SHA256, ignoreCase = true)) {
                throw IOException("فشل التحقق من بصمة تحسين الصوت. لم يتم استبدال النموذج.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun replaceFileSafely(source: File, destination: File) {
        val backup = File(destination.parentFile, destination.name + ".sawt-backup")
        backup.delete()
        if (destination.exists() && !destination.renameTo(backup)) {
            throw IOException("تعذر تجهيز ملف محرك الصوت للاستبدال.")
        }
        if (!source.renameTo(destination)) {
            if (backup.exists()) backup.renameTo(destination)
            throw IOException("تعذر تثبيت ملف محرك الصوت المحسّن.")
        }
        backup.delete()
    }

    private fun downloadVerifiedAsset(
        asset: ModelAsset,
        destination: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val partFile = File(destination.parentFile, destination.name + ".part")
        partFile.delete()
        val connection = (URL(asset.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 120_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "SAWT-AI-Android")
            setRequestProperty("Accept-Encoding", "identity")
        }
        var completed = false
        try {
            connection.connect()
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("تعذر تنزيل ${asset.fileName} (HTTP $status).")
            }

            val total = connection.contentLengthLong.takeIf { it > 0 }
                ?: asset.expectedBytes.takeIf { it > 0 }
                ?: asset.minimumBytes
            var downloaded = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(connection.inputStream).use { input ->
                val fileOutput = FileOutputStream(partFile)
                BufferedOutputStream(fileOutput).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }

            if (downloaded < asset.minimumBytes) {
                throw IOException("تنزيل ${asset.fileName} غير مكتمل. أعد المحاولة.")
            }
            if (asset.expectedBytes > 0L && downloaded != asset.expectedBytes) {
                throw IOException("حجم ${asset.fileName} لا يطابق الحجم المتوقع. أُوقف التثبيت.")
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!actualSha.equals(asset.sha256, ignoreCase = true)) {
                throw IOException("فشل فحص سلامة ${asset.fileName}. لم يتم تثبيت الملف.")
            }

            if (destination.exists() && !destination.delete()) {
                throw IOException("تعذر استبدال الملف المؤقت ${asset.fileName}.")
            }
            if (!partFile.renameTo(destination)) {
                throw IOException("تعذر اعتماد الملف ${asset.fileName} بعد فحصه.")
            }
            completed = true
        } finally {
            connection.disconnect()
            if (!completed) partFile.delete()
        }
    }

    companion object {
        // Prevent duplicate native model instances from synthesizing concurrently
        // when Android recreates the Activity or Compose screen.
        private val processOperationMutex = Mutex()

        const val MODEL_DIRECTORY = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
        private const val MAX_TEXT_CHARACTERS = 2_000
        private const val MAX_TEXT_BYTES = 5_000
        private const val MAX_CHUNK_CHARACTERS = 180
        private const val WAV_MINIMUM_BYTES = 44L
        const val ARCHIVE_SHA256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427"
        const val OPTIMIZED_VECTOR_URL =
            "https://huggingface.co/WilburDev/supertonic-3-mobile/resolve/2d53d752a6e9a1db1bd0f63fe36806dc6d1a3ee5/vector_estimator.int8.onnx"
        const val OPTIMIZED_VECTOR_SHA256 = "6fd0f1637a0192ec2ec160a899045d8138395100a3c25414134adf76abd3f57b"
        const val OPTIMIZED_VECTOR_SIZE_BYTES = 66_000_000L
        const val MIN_OPTIMIZED_VECTOR_SIZE_BYTES = 60L * 1024L * 1024L
        const val VECTOR_FILE = "vector_estimator.int8.onnx"
        const val OPTIMIZED_INSTALL_MARKER = ARCHIVE_SHA256 + ":" + OPTIMIZED_VECTOR_SHA256
        const val DIRECT_INSTALL_MARKER = "hf-cca5a0e6:" + "mobile-2d53d75:" + OPTIMIZED_VECTOR_SHA256
        const val MINIMUM_FREE_SPACE_BYTES = 350L * 1024L * 1024L
        const val MINIMUM_OPTIMIZATION_SPACE_BYTES = 160L * 1024L * 1024L

        private const val UPSTREAM_MODEL_BASE =
            "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/cca5a0e6c96e1d2c720986bf7e75fcc81dee3ae4/"

        private val MODEL_ASSETS = listOf(
            ModelAsset(
                "duration_predictor.int8.onnx",
                UPSTREAM_MODEL_BASE + "duration_predictor.int8.onnx",
                3_700_147L, 3_700_147L,
                "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"
            ),
            ModelAsset(
                "text_encoder.int8.onnx",
                UPSTREAM_MODEL_BASE + "text_encoder.int8.onnx",
                36_416_150L, 36_416_150L,
                "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"
            ),
            ModelAsset(
                VECTOR_FILE,
                OPTIMIZED_VECTOR_URL,
                0L, MIN_OPTIMIZED_VECTOR_SIZE_BYTES,
                OPTIMIZED_VECTOR_SHA256
            ),
            ModelAsset(
                "vocoder.int8.onnx",
                UPSTREAM_MODEL_BASE + "vocoder.int8.onnx",
                25_991_073L, 25_991_073L,
                "e923d60f53f95eb1ce235f1dc33ec56d9c057823c96fa6f8acf98f32b0da6152"
            ),
            ModelAsset(
                "tts.json",
                UPSTREAM_MODEL_BASE + "tts.json",
                8_253L, 8_253L,
                "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"
            ),
            ModelAsset(
                "unicode_indexer.bin",
                UPSTREAM_MODEL_BASE + "unicode_indexer.bin",
                262_144L, 262_144L,
                "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30"
            ),
            ModelAsset(
                "voice.bin",
                UPSTREAM_MODEL_BASE + "voice.bin",
                517_168L, 517_168L,
                "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8"
            )
        )

        private val requiredModelFiles = listOf(
            "duration_predictor.int8.onnx" to 1_000_000L,
            "text_encoder.int8.onnx" to 10_000_000L,
            "vector_estimator.int8.onnx" to 40_000_000L,
            "vocoder.int8.onnx" to 10_000_000L,
            "tts.json" to 1_000L,
            "unicode_indexer.bin" to 1_000L,
            "voice.bin" to 100_000L
        )
    }
}
