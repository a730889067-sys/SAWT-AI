package com.sawt.ai.audio

import android.content.Context
import android.os.StatFs
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Native, on-device Supertonic 3 engine.
 * Model archives are verified before extraction and remain in app-private storage.
 */
class SupertonicTtsEngine(context: Context) {
    private val appContext = context.applicationContext
    private val modelsRoot = File(appContext.filesDir, "sawt-models")
    private val modelDirectory = File(modelsRoot, MODEL_DIRECTORY)
    private var nativeTts: OfflineTts? = null

    fun isModelInstalled(): Boolean {
        val marker = File(modelDirectory, ".sawt-verified")
        if (!marker.isFile) return false
        val markerValue = marker.readText().trim()
        if (markerValue != ARCHIVE_SHA256 && markerValue != OPTIMIZED_INSTALL_MARKER) return false
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

        if (needsOptimization()) {
            onStage("جارٍ تنزيل تحسين السرعة للهواتف…")
            upgradeInstalledModel(onProgress)
            onStage("اكتمل تحسين محرك الصوت. يمكنك التحويل دون إنترنت.")
            return@withContext
        }

        val availableBytes = StatFs(appContext.filesDir.absolutePath).availableBytes
        if (availableBytes < MINIMUM_FREE_SPACE_BYTES) {
            throw IOException("يلزم توفر 600 ميجابايت تقريبًا من المساحة الفارغة لإكمال تنزيل الأصوات وتثبيتها.")
        }

        modelsRoot.mkdirs()
        val archive = File(appContext.cacheDir, "$MODEL_DIRECTORY.tar.bz2")
        val extractingRoot = File(modelsRoot, ".supertonic-installing")
        archive.delete()
        extractingRoot.deleteRecursively()
        onStage("جارٍ تنزيل حزمة الأصوات (حوالي 129 ميجابايت)…")

        try {
            downloadArchive(archive, onProgress)
            onStage("تم التنزيل. جارٍ التحقق من سلامة الملف…")
            val actualSha = sha256(archive)
            if (!actualSha.equals(ARCHIVE_SHA256, ignoreCase = true)) {
                throw IOException("فشل التحقق من بصمة الملف. حُذفت النسخة غير السليمة؛ أعد المحاولة.")
            }

            onStage("التحقق سليم. جارٍ تثبيت ملفات النماذج…")
            extractArchiveSafely(archive, extractingRoot, onStage)

            val extractedModelDirectory = File(extractingRoot, MODEL_DIRECTORY)
            val missing = requiredModelFiles.firstOrNull { (name, minimumBytes) ->
                val file = File(extractedModelDirectory, name)
                !file.isFile || file.length() < minimumBytes
            }
            if (missing != null) {
                throw IOException("ملفات النموذج غير مكتملة (${missing.first}). أُوقف التثبيت لحماية التطبيق.")
            }

            onStage("جارٍ تنزيل محرك Supertonic المحسّن للهواتف (حوالي 66 ميجابايت)…")
            val optimizedVector = File(appContext.cacheDir, "vector_estimator.mobile.tmp")
            downloadOptimizedVectorEstimator(optimizedVector, onProgress)
            replaceFileSafely(optimizedVector, File(extractedModelDirectory, VECTOR_FILE))

            // Avoid four full speech generations during installation.
            // The model archive and every required model file have already
            // been SHA-256/size checked. Load ONNX only when the user previews
            // or converts speech, and keep that live engine cached in memory.
            onStage("تم التحقق من ملفات النموذج. يجري تجهيزها للاستخدام…")

            File(extractedModelDirectory, ".sawt-verified").writeText(OPTIMIZED_INSTALL_MARKER)
            modelDirectory.deleteRecursively()
            if (!extractedModelDirectory.renameTo(modelDirectory)) {
                throw IOException("تعذر نقل ملفات الصوت إلى مساحة التطبيق.")
            }
            onStage("اكتمل تثبيت الأصوات العشرة. يمكنك الآن العمل دون إنترنت.")
        } catch (error: Exception) {
            extractingRoot.deleteRecursively()
            modelDirectory.deleteRecursively()
            throw error
        } finally {
            archive.delete()
        }
    }

    suspend fun synthesizeToFile(
        text: String,
        language: String,
        speakerId: Int,
        speed: Float
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
        val audio = try {
            engine.generateWithConfig(spokenText, config)
        } catch (error: OutOfMemoryError) {
            close()
            throw IOException("ذاكرة الهاتف لم تكفِ لإنشاء هذا المقطع. جرّب نصًا أقصر وأغلق التطبيقات الأخرى.", error)
        }
        if (audio.samples.isEmpty() || audio.sampleRate <= 0) {
            throw IOException("لم ينتج محرك الصوت ملفًا صالحًا. جرّب نصًا أقصر.")
        }

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
            // Keep ONNX CPU workers bounded on low-memory Android phones.
            // More workers can multiply intermediate tensor buffers and trigger
            // memory pressure on devices with only 2 GB RAM.
            numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 2)
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

    private fun downloadArchive(
        destination: File,
        onProgress: (Long, Long) -> Unit
    ) {
        val connection = (URL(ARCHIVE_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "SAWT-AI-Android")
        }
        try {
            connection.connect()
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("تعذر تنزيل حزمة الأصوات (HTTP $status).")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: ARCHIVE_SIZE_BYTES
            var downloaded = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(destination)).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                    output.flush()
                }
            }
            if (downloaded != ARCHIVE_SIZE_BYTES) {
                throw IOException("حجم التنزيل غير مكتمل. أعد المحاولة مع اتصال إنترنت مستقر.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractArchiveSafely(archive: File, destinationRoot: File, onStage: (String) -> Unit) {
        destinationRoot.mkdirs()
        val canonicalRoot = destinationRoot.canonicalFile
        var expandedBytes = 0L
        var filesWritten = 0
        BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)), true).use { bzip ->
            TarArchiveInputStream(bzip).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val target = File(destinationRoot, entry.name)
                    val canonicalTarget = target.canonicalFile
                    if (!canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) {
                        throw IOException("تحتوي حزمة الصوت على مسار غير آمن.")
                    }
                    if (entry.isDirectory) {
                        if (!target.exists() && !target.mkdirs()) throw IOException("تعذر إنشاء مجلد النموذج.")
                    } else if (entry.isFile) {
                        if (entry.size < 0 || entry.size > MAX_SINGLE_FILE_BYTES) {
                            throw IOException("حجم أحد ملفات النموذج غير متوقع.")
                        }
                        target.parentFile?.let { parent ->
                            if (!parent.exists() && !parent.mkdirs()) throw IOException("تعذر تجهيز مجلد النموذج.")
                        }
                        BufferedOutputStream(FileOutputStream(target)).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = tar.read(buffer)
                                if (count < 0) break
                                expandedBytes += count
                                if (expandedBytes > MAX_TOTAL_EXTRACTED_BYTES) {
                                    throw IOException("تجاوزت ملفات النموذج الحجم الآمن المسموح.")
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                        filesWritten++
                    }
                }
            }
        }
        if (filesWritten < requiredModelFiles.size) throw IOException("لم تكتمل ملفات النموذج أثناء التثبيت.")
        onStage("اكتمل فك ضغط ملفات الأصوات والتحقق منها.")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MODEL_DIRECTORY = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
        const val ARCHIVE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$MODEL_DIRECTORY.tar.bz2"
        const val ARCHIVE_SHA256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427"
        const val ARCHIVE_SIZE_BYTES = 128_774_318L
        const val OPTIMIZED_VECTOR_URL =
            "https://huggingface.co/WilburDev/supertonic-3-mobile/resolve/main/vector_estimator.int8.onnx"
        const val OPTIMIZED_VECTOR_SHA256 = "6fd0f1637a0192ec2ec160a899045d8138395100a3c25414134adf76abd3f57b"
        const val OPTIMIZED_VECTOR_SIZE_BYTES = 66_000_000L
        const val MIN_OPTIMIZED_VECTOR_SIZE_BYTES = 60L * 1024L * 1024L
        const val VECTOR_FILE = "vector_estimator.int8.onnx"
        const val OPTIMIZED_INSTALL_MARKER = ARCHIVE_SHA256 + ":" + OPTIMIZED_VECTOR_SHA256
        const val MINIMUM_FREE_SPACE_BYTES = 600L * 1024L * 1024L
        const val MINIMUM_OPTIMIZATION_SPACE_BYTES = 160L * 1024L * 1024L
        const val MAX_SINGLE_FILE_BYTES = 350L * 1024L * 1024L
        const val MAX_TOTAL_EXTRACTED_BYTES = 500L * 1024L * 1024L

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
