package com.sawt.ai.audio

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/** Durable, bounded local library for WAV and MP3 recordings. */
data class AudioHistoryEntry(
    val id: String,
    val createdAtMillis: Long,
    val language: String,
    val voiceName: String,
    val engineId: String,
    val speed: Float,
    val qualitySteps: Int,
    val text: String,
    val format: String = "wav"
)

class AudioHistoryStore(
    private val root: File,
    private val maxEntries: Int = 100,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES
) {
    private val indexFile = File(root, "history.tsv")
    private val lock = Any()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxTotalBytes >= MIN_AUDIO_BYTES) { "maxTotalBytes is too small" }
    }

    fun list(): List<AudioHistoryEntry> = synchronized(lock) {
        readEntries().sortedByDescending { it.createdAtMillis }
    }

    fun audioFile(entry: AudioHistoryEntry): File? = synchronized(lock) {
        if (!isSafeId(entry.id) || entry.format !in SUPPORTED_FORMATS) return@synchronized null
        val file = recordingFile(entry)
        file.takeIf { it.isFile && isValidAudio(it, entry.format) }
    }

    @Throws(IOException::class)
    fun add(
        source: File,
        language: String,
        voiceName: String,
        engineId: String,
        speed: Float,
        qualitySteps: Int,
        text: String,
        createdAtMillis: Long = System.currentTimeMillis()
    ): AudioHistoryEntry = synchronized(lock) {
        if (!source.isFile) throw IOException("ملف التسجيل غير موجود.")
        val format = detectFormat(source) ?: throw IOException("ملف التسجيل ليس WAV أو MP3 صالحًا.")
        if (!isValidAudio(source, format)) throw IOException("ملف التسجيل غير مكتمل.")
        if (language !in setOf("ar", "en")) throw IOException("لغة التسجيل غير صالحة.")
        if (text.isBlank() || text.length > MAX_TEXT_CHARS ||
            text.toByteArray(StandardCharsets.UTF_8).size > MAX_TEXT_BYTES) {
            throw IOException("النص المحفوظ يتجاوز الحد الآمن.")
        }

        val isCloudEngine = engineId.matches(CLOUD_ENGINE_REGEX)
        val validEngine = engineId in LOCAL_ENGINES || isCloudEngine
        val validQuality = when {
            engineId == "nabra" || isCloudEngine -> qualitySteps == 0
            engineId == "supertonic" -> qualitySteps in setOf(2, 4, 6, 8)
            else -> false
        }
        if (!validEngine || !speed.isFinite() || !validQuality) {
            throw IOException("إعدادات التسجيل أو مصدر الصوت غير صالحة.")
        }
        if (voiceName.isBlank() || voiceName.length > MAX_VOICE_NAME_CHARS) {
            throw IOException("اسم الصوت غير صالح.")
        }
        if (!root.exists() && !root.mkdirs()) throw IOException("تعذر إنشاء مكتبة التسجيلات.")

        val id = UUID.randomUUID().toString()
        val entry = AudioHistoryEntry(
            id = id,
            createdAtMillis = createdAtMillis,
            language = language,
            voiceName = voiceName,
            engineId = engineId,
            speed = speed.coerceIn(0.5f, 2.0f),
            qualitySteps = qualitySteps,
            text = text,
            format = format
        )
        val destination = recordingFile(entry)
        val partial = File(root, "$id.$format.part")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(partial).use { output ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                    output.flush()
                    output.fd.sync()
                }
            }
            if (!isValidAudio(partial, format)) throw IOException("تعذر التحقق من ملف الصوت بعد نسخه.")
            if (!partial.renameTo(destination)) throw IOException("تعذر حفظ التسجيل في المكتبة.")
        } catch (error: Exception) {
            partial.delete()
            destination.delete()
            if (error is IOException) throw error
            throw IOException("تعذر حفظ التسجيل.", error)
        }

        if (destination.length() > maxTotalBytes) {
            destination.delete()
            throw IOException("التسجيل أكبر من المساحة المخصصة لمكتبة التسجيلات؛ نزّله مباشرة.")
        }
        val all = readEntries().filter { it.id != id }.sortedByDescending { it.createdAtMillis }
        val keep = ArrayList<AudioHistoryEntry>()
        var retainedBytes = destination.length()
        keep.add(entry)
        for (old in all) {
            if (keep.size >= maxEntries) break
            val oldFile = recordingFile(old)
            val oldSize = oldFile.length()
            if (oldSize <= 0L) continue
            if (retainedBytes > maxTotalBytes - oldSize) break
            keep.add(old)
            retainedBytes += oldSize
        }
        try {
            writeEntries(keep)
        } catch (error: Exception) {
            destination.delete()
            throw IOException("تعذر تحديث فهرس مكتبة التسجيلات.", error)
        }
        val retainedIds = keep.mapTo(HashSet()) { it.id }
        all.filterNot { it.id in retainedIds }.forEach { old ->
            if (isSafeId(old.id)) recordingFile(old).delete()
        }
        entry
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        if (!isSafeId(id)) return@synchronized false
        val entries = readEntries()
        val target = entries.firstOrNull { it.id == id } ?: return@synchronized false
        val newEntries = entries.filterNot { it.id == id }
        writeEntries(newEntries)
        recordingFile(target).delete()
        true
    }

    private fun readEntries(): List<AudioHistoryEntry> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            indexFile.readLines(Charsets.UTF_8).mapNotNull { line ->
                runCatching { decodeLine(line) }.getOrNull()
            }.filter { entry ->
                isSafeId(entry.id) && recordingFile(entry).let {
                    it.isFile && isValidAudio(it, entry.format)
                }
            }.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    private fun decodeLine(line: String): AudioHistoryEntry? {
        val fields = line.split('|')
        // Eight-field rows are the legacy WAV-only format used by earlier SAWT builds.
        if (fields.size !in LEGACY_FIELD_COUNT..FIELD_COUNT) return null
        val entry = AudioHistoryEntry(
            id = fields[0],
            createdAtMillis = fields[1].toLong(),
            language = fields[2],
            voiceName = decode(fields[3]),
            engineId = fields[4],
            speed = fields[5].toFloat(),
            qualitySteps = fields[6].toInt(),
            text = decode(fields[7]),
            format = fields.getOrNull(8) ?: "wav"
        )
        val isCloudEngine = entry.engineId.matches(CLOUD_ENGINE_REGEX)
        val validEngine = entry.engineId in LOCAL_ENGINES || isCloudEngine
        val validQuality = when {
            entry.engineId == "nabra" || isCloudEngine -> entry.qualitySteps == 0
            entry.engineId == "supertonic" -> entry.qualitySteps in setOf(2, 4, 6, 8)
            else -> false
        }
        if (!isSafeId(entry.id) || entry.language !in setOf("ar", "en") ||
            !validEngine || !validQuality || entry.format !in SUPPORTED_FORMATS ||
            !entry.speed.isFinite() || entry.speed !in 0.5f..2.0f ||
            entry.voiceName.isBlank() || entry.voiceName.length > MAX_VOICE_NAME_CHARS ||
            entry.text.isBlank() || entry.text.length > MAX_TEXT_CHARS ||
            entry.text.toByteArray(StandardCharsets.UTF_8).size > MAX_TEXT_BYTES
        ) return null
        return entry
    }

    private fun writeEntries(entries: List<AudioHistoryEntry>) {
        if (!root.exists() && !root.mkdirs()) throw IOException("تعذر إنشاء مكتبة التسجيلات.")
        val temp = File(root, "history.tsv.part")
        try {
            temp.bufferedWriter(Charsets.UTF_8).use { writer ->
                entries.sortedByDescending { it.createdAtMillis }.take(maxEntries).forEach { entry ->
                    writer.append(
                        listOf(
                            entry.id,
                            entry.createdAtMillis.toString(),
                            entry.language,
                            encode(entry.voiceName),
                            entry.engineId,
                            entry.speed.toString(),
                            entry.qualitySteps.toString(),
                            encode(entry.text),
                            entry.format
                        ).joinToString("|")
                    )
                    writer.newLine()
                }
            }
            FileOutputStream(temp, true).use { it.fd.sync() }
            val backup = File(root, "history.tsv.bak")
            if (indexFile.exists()) {
                backup.delete()
                if (!indexFile.renameTo(backup)) throw IOException("تعذر تحديث فهرس التسجيلات.")
            }
            if (!temp.renameTo(indexFile)) {
                if (backup.exists()) backup.renameTo(indexFile)
                throw IOException("تعذر اعتماد فهرس التسجيلات.")
            }
            backup.delete()
        } finally {
            temp.delete()
        }
    }

    private fun recordingFile(entry: AudioHistoryEntry): File =
        File(root, "${entry.id}.${entry.format}")

    private fun detectFormat(file: File): String? = when {
        isWav(file) -> "wav"
        isMp3(file) -> "mp3"
        else -> null
    }

    private fun isValidAudio(file: File, format: String): Boolean = when (format) {
        "wav" -> file.length() >= WAV_HEADER_BYTES && isWav(file)
        "mp3" -> file.length() >= MP3_MIN_BYTES && isMp3(file)
        else -> false
    }

    private fun isWav(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(12)
            if (input.read(header) != header.size) return@use false
            String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
        }
    }.getOrDefault(false)

    private fun isMp3(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(3)
            if (input.read(header) != header.size) return@use false
            String(header, 0, 3, Charsets.US_ASCII) == "ID3" ||
                ((header[0].toInt() and 0xff) == 0xff && (header[1].toInt() and 0xe0) == 0xe0)
        }
    }.getOrDefault(false)

    private fun isSafeId(value: String): Boolean =
        value.matches(Regex("[0-9a-fA-F-]{36}"))

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

    companion object {
        private const val WAV_HEADER_BYTES = 44L
        private const val MP3_MIN_BYTES = 256L
        private const val MIN_AUDIO_BYTES = 44L
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MAX_TEXT_CHARS = 2_000
        private const val MAX_TEXT_BYTES = 5_000
        private const val MAX_VOICE_NAME_CHARS = 64
        private const val DEFAULT_MAX_TOTAL_BYTES = 1024L * 1024L * 1024L
        private const val LEGACY_FIELD_COUNT = 8
        private const val FIELD_COUNT = 9
        private val SUPPORTED_FORMATS = setOf("wav", "mp3")
        private val LOCAL_ENGINES = setOf("supertonic", "nabra")
        private val CLOUD_ENGINE_REGEX = Regex("cloud_[a-z0-9-]{1,48}")
    }
}
