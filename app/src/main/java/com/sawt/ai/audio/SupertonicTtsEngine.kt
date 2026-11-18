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
        if (!marker.isFile || marker.readText().trim() != ARCHIVE_SHA256) return false
        return requiredModelFiles.all { (name, minimumBytes) ->
            File(modelDirectory, name).let { it.isFile && it.length() >= minimumBytes }
        }
    }

    suspend fun downloadAndInstall(
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        onStage: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (isModelInstalled()) {
            onStage("حزمة الأصوات موجودة وسليمة بالفعل.")
            return@withContext
        }

        val availableBytes = StatFs(appContext.filesDir.absolutePath).availableBytes
        if (availableBytes < MINIMUM_FREE_SPACE_BYTES) {
            throw IOException("يلزم توفر 380 ميجابايت تقريبًا من المساحة الفارغة لإكمال تنزيل الأصوات وتثبيتها.")
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
                throw IOException("ملفات النموذج غير مكتملة (\${missing.first}). أُوقف التثبيت لحماية التطبيق.")
            }

            File(extractedModelDirectory, ".sawt-verified").writeText(ARCHIVE_SHA256)
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
        speed: Float,
        qualitySteps: Int
    ): File = withContext(Dispatchers.IO) {
        val spokenText = text.trim()
        if (spokenText.isEmpty()) throw IOException("اكتب النص أولًا.")
        if (spokenText.length > 2000 || spokenText.toByteArray(Charsets.UTF_8).size > 5000) {
            throw IOException("النص طويل. قسّمه إلى فقرات لا تتجاوز 2000 حرف.")
        }
        if (language !in setOf("ar", "en")) throw IOException("اختر العربية أو الإنجليزية.")
        if (speakerId !in 0..9) throw IOException("اختيار الصوت غير صالح.")
        if (!isModelInstalled()) throw IOException("نزّل حزمة الأصوات أولًا.")

        val engine = nativeTts ?: createNativeEngine().also { nativeTts = it }
        val config = GenerationConfig(
            sid = speakerId,
            speed = speed.coerceIn(0.8f, 1.2f),
            numSteps = qualitySteps.coerceIn(5, 12),
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

        val output = File(appContext.cacheDir, "sawt-\${System.currentTimeMillis()}.wav")
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

    private fun createNativeEngine(): OfflineTts {
        val config = getOfflineTtsConfig(
            modelDir = modelDirectory.absolutePath,
            isSupertonic = true,
            durationPredictor = "duration_predictor.int8.onnx",
            textEncoder = "text_encoder.int8.onnx",
            vectorEstimator = "vector_estimator.int8.onnx",
            supertonicVocoder = "vocoder.int8.onnx",
            ttsJson = "tts.json",
            unicodeIndexer = "unicode_indexer.bin",
            voiceStyle = "voice.bin",
            numThreads = 2
        )
        return OfflineTts(config = config)
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
        const val MINIMUM_FREE_SPACE_BYTES = 380L * 1024L * 1024L
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
