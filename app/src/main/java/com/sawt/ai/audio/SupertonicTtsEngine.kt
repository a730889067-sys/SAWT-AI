package com.sawt.ai.audio

import android.content.Context
import android.os.StatFs
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
    private var nativeTts: OfflineTts? = null

    fun isModelInstalled(): Boolean {
        val marker = File(modelDirectory, ".sawt-verified")
        if (!marker.isFile) return false
        val markerValue = marker.readText().trim()
        if (markerValue != ARCHIVE_SHA256 && markerValue != OPTIMIZED_INSTALL_MARKER && markerValue != DIRECT_INSTALL_MARKER) return false
        return requiredModelFiles.all { (name, minimumBytes) ->
            File(modelDirectory, name).let { it.isFile && it.length() >= minimumBytes }
        }
    }

    fun needsOptimization(): Boolean {
        val marker = File(modelDirectory, ".sawt-verified")
        return isModelInstalled() && marker.readText().trim() == ARCHIVE_SHA256
    }

    suspend fun downloadAndInstall(
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        onStage: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (isModelInstalled() && !needsOptimization()) {
            onStage("حزمة الأصوات المحسّنة موجودة وسليمة بالفعل.")
            return@withContext
        }

        // Keep compatibility with installations created by older app versions.
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

        modelsRoot.mkdirs()
        // Remove an abandoned archive from a previous installation attempt.
        File(appContext.cacheDir, "$MODEL_DIRECTORY.tar.bz2").delete()
        File(modelsRoot, ".supertonic-installing").deleteRecursively()
        File(modelsRoot, ".supertonic-previous").deleteRecursively()
        File(appContext.cacheDir, "vector_estimator.mobile.tmp").delete()
        File(appContext.cacheDir, "vector_estimator.mobile.tmp").delete()

        val stagingRoot = File(modelsRoot, ".supertonic-installing")
        val backupRoot = File(modelsRoot, ".supertonic-previous")
        stagingRoot.deleteRecursively()
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

            close()
            backupRoot.deleteRecursively()
            if (modelDirectory.exists() && !modelDirectory.renameTo(backupRoot)) {
                throw IOException("تعذر تجهيز ملفات الصوت القديمة للاستبدال.")
            }
            if (!stagingRoot.renameTo(modelDirectory)) {
                if (backupRoot.exists()) backupRoot.renameTo(modelDirectory)
                throw IOException("تعذر اعتماد ملفات الصوت بعد التحقق منها.")
            }
            backupRoot.deleteRecursively()
            onStage("اكتمل تثبيت الأصوات العشرة. يمكنك الآن العمل دون إنترنت.")
        } catch (error: Exception) {
            stagingRoot.deleteRecursively()
            throw error
        }
    }

    suspend fun synthesizeToFile(
        text: String,
        language: String,
        speakerId: Int,
        speed: Float,
        onStage: (String) -> Unit = {},
        onProgress: (generatedAudioMs: Long) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val spokenText = text.trim()
        if (spokenText.isEmpty()) throw IOException("اكتب النص أولًا.")
        if (spokenText.length > 2000 || spokenText.toByteArray(Charsets.UTF_8).size > 5000) {
            throw IOException("النص طويل. قسّمه إلى فقرات لا تتجاوز 2000 حرف.")
        }
        if (language !in setOf("ar", "en")) throw IOException("اختر العربية أو الإنجليزية.")
        if (speakerId !in 0..9) throw IOException("اختيار الصوت غير صالح.")
        if (!isModelInstalled()) throw IOException("نزّل حزمة الأصوات أولًا.")

        val engine = nativeTts ?: try {
            onStage("جارٍ تجهيز محرك الصوت على الهاتف…")
            createNativeEngine().also { nativeTts = it }
        } catch (error: OutOfMemoryError) {
            close()
            throw IOException("ذاكرة الهاتف غير كافية لتحميل محرك الصوت. أغلق التطبيقات الأخرى وأعد المحاولة.", error)
        }
        val config = GenerationConfig(
            sid = speakerId,
            speed = speed.coerceIn(0.8f, 1.2f),
            // Supertonic quality steps increase inference work. On this app's
            // low-memory target, keep the fast preset as a hard upper bound.
            numSteps = 4,
            extra = mapOf("lang" to language)
        )
        onStage("جارٍ توليد الصوت…")
        var generatedSamples = 0L
        val sampleRate = engine.sampleRate().coerceAtLeast(1)
        val audio = try {
            engine.generateWithConfigAndCallback(spokenText, config) { samples ->
                generatedSamples += samples.size.toLong()
                onProgress(generatedSamples * 1_000L / sampleRate)
                1
            }
        } catch (error: OutOfMemoryError) {
            close()
            throw IOException("ذاكرة الهاتف لم تكفِ لإنشاء هذا المقطع. جرّب نصًا أقصر وأغلق التطبيقات الأخرى.", error)
        }
        if (audio.samples.isEmpty() || audio.sampleRate <= 0) {
            throw IOException("لم ينتج محرك الصوت ملفًا صالحًا. جرّب نصًا أقصر.")
        }

        onStage("اكتمل توليد المقطع. جارٍ حفظ ملف الصوت…")
        val output = File(appContext.cacheDir, "sawt-${System.currentTimeMillis()}.wav")
        if (!audio.save(output.absolutePath) || !output.isFile || output.length() < 44L) {
            output.delete()
            throw IOException("تعذر حفظ الصوت الناتج.")
        }
        output
    }

    fun deleteDownloadedModels() {
        close()
        modelsRoot.deleteRecursively()
        File(appContext.cacheDir, "$MODEL_DIRECTORY.tar.bz2").delete()
    }

    fun close() {
        try {
            nativeTts?.release()
        } catch (_: Exception) {
        }
        nativeTts = null
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
            // Use up to four CPU workers: the mobile-optimized vector estimator
            // rewrites pointwise Conv1d layers as MatMul so ONNX Runtime can
            // parallelize them across cores. Four workers are a compromise
            // for 2 GB Android devices; never exceed the cores reported by Android.
            numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
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
            close()
            replaceFileSafely(optimizedVector, File(modelDirectory, VECTOR_FILE))
            File(modelDirectory, ".sawt-verified").writeText(OPTIMIZED_INSTALL_MARKER)
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
                BufferedOutputStream(FileOutputStream(destination)).use { output ->
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
                }
            }
            if (downloaded < MIN_OPTIMIZED_VECTOR_SIZE_BYTES) {
                throw IOException("ملف تحسين الصوت غير مكتمل. أعد المحاولة.")
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
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
                BufferedOutputStream(FileOutputStream(partFile)).use { output ->
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
                }
            }

            if (downloaded < asset.minimumBytes) {
                throw IOException("تنزيل ${asset.fileName} غير مكتمل. أعد المحاولة.")
            }
            if (asset.expectedBytes > 0L && downloaded != asset.expectedBytes) {
                throw IOException("حجم ${asset.fileName} لا يطابق الحجم المتوقع. أُوقف التثبيت.")
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
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
        const val MODEL_DIRECTORY = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
        const val ARCHIVE_SHA256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427"
        const val OPTIMIZED_VECTOR_URL =
            "https://huggingface.co/WilburDev/supertonic-3-mobile/resolve/2d53d75/vector_estimator.int8.onnx"
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
