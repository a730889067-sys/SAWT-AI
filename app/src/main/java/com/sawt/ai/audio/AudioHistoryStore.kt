package com.sawt.ai.audio

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/** Durable, bounded local library for previously generated recordings. */
data class AudioHistoryEntry(
    val id: String,
    val createdAtMillis: Long,
    val language: String,
    val voiceName: String,
    val engineId: String,
    val speed: Float,
    val qualitySteps: Int,
    val text: String
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
        require(maxTotalBytes >= WAV_HEADER_BYTES) { "maxTotalBytes is too small" }
    }

    fun list(): List<AudioHistoryEntry> = synchronized(lock) {
        readEntries().sortedByDescending { it.createdAtMillis }
    }

    fun audioFile(entry: AudioHistoryEntry): File? = synchronized(lock) {
        if (!isSafeId(entry.id)) return@synchronized null
        File(root, "${entry.id}.wav").takeIf { it.isFile && it.length() >= WAV_HEADER_BYTES }
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
        if (!source.isFile || source.length() < WAV_HEADER_BYTES) {
            throw IOException("ملف التسجيل غير موجود أو غير مكتمل.")
        }
        if (!isWav(source)) throw IOException("ملف التسجيل ليس WAV صالحًا.")
        if (language !in setOf("ar", "en")) throw IOException("لغة التسجيل غير صالحة.")
        if (text.isBlank() || text.length > MAX_TEXT_CHARS || text.toByteArray(StandardCharsets.UTF_8).size > MAX_TEXT_BYTES) {
            throw IOException("النص المحفوظ يتجاوز الحد الآمن.")
        }
        val validQuality = if (engineId == "nabra") qualitySteps == 0 else qualitySteps in setOf(2, 4, 6, 8)
        if (!speed.isFinite() || !validQuality) {
            throw IOException("إعدادات التسجيل غير صالحة.")
        }
        if (voiceName.isBlank() || engineId !in setOf("supertonic", "nabra")) {
            throw IOException("مصدر الصوت غير صالح.")
        }
        if (!root.exists() && !root.mkdirs()) throw IOException("تعذر إنشاء مكتبة التسجيلات.")
        val id = UUID.randomUUID().toString()
        val destination = File(root, "$id.wav")
        val partial = File(root, "$id.wav.part")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(partial).use { output ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                    output.flush()
                    output.fd.sync()
                }
            }
            if (!isWav(partial)) throw IOException("تعذر التحقق من ملف WAV بعد نسخه.")
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
        val entry = AudioHistoryEntry(
            id = id,
            createdAtMillis = createdAtMillis,
            language = language,
            voiceName = voiceName.take(MAX_VOICE_NAME_CHARS),
            engineId = engineId,
            speed = speed.coerceIn(0.8f, 1.2f),
            qualitySteps = qualitySteps,
            text = text
        )
        val all = readEntries().filter { it.id != id }.sortedByDescending { it.createdAtMillis }
        val keep = ArrayList<AudioHistoryEntry>()
        var retainedBytes = destination.length()
        keep.add(entry)
        for (old in all) {
            if (keep.size >= maxEntries) break
            val oldFile = File(root, "${old.id}.wav")
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
            if (isSafeId(old.id)) File(root, "${old.id}.wav").delete()
        }
        entry
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        if (!isSafeId(id)) return@synchronized false
        val entries = readEntries()
        val newEntries = entries.filterNot { it.id == id }
        if (newEntries.size == entries.size) return@synchronized false
        writeEntries(newEntries)
        File(root, "$id.wav").delete()
        true
    }

    private fun readEntries(): List<AudioHistoryEntry> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            indexFile.readLines(Charsets.UTF_8).mapNotNull { line ->
                runCatching { decodeLine(line) }.getOrNull()
            }.filter { entry ->
                isSafeId(entry.id) && File(root, "${entry.id}.wav").let {
                    it.isFile && it.length() >= WAV_HEADER_BYTES
                }
            }.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    private fun decodeLine(line: String): AudioHistoryEntry? {
        val fields = line.split('|')
        if (fields.size != FIELD_COUNT) return null
        val entry = AudioHistoryEntry(
            id = fields[0],
            createdAtMillis = fields[1].toLong(),
            language = fields[2],
            voiceName = decode(fields[3]),
            engineId = fields[4],
            speed = fields[5].toFloat(),
            qualitySteps = fields[6].toInt(),
            text = decode(fields[7])
        )
        if (!isSafeId(entry.id) || entry.language !in setOf("ar", "en") ||
            entry.engineId !in setOf("supertonic", "nabra") ||
            !entry.speed.isFinite() ||
            !(if (entry.engineId == "nabra") entry.qualitySteps == 0 else entry.qualitySteps in setOf(2, 4, 6, 8)) ||
            entry.text.isBlank() || entry.text.length > MAX_TEXT_CHARS
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
                            encode(entry.text)
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

    private fun isWav(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(12)
            if (input.read(header) != header.size) return@use false
            String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
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
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MAX_TEXT_CHARS = 2_000
        private const val MAX_TEXT_BYTES = 5_000
        private const val MAX_VOICE_NAME_CHARS = 64
        private const val DEFAULT_MAX_TOTAL_BYTES = 1024L * 1024L * 1024L
        private const val FIELD_COUNT = 8
    }
}
