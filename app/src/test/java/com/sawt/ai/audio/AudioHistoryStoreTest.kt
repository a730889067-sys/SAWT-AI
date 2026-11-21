package com.sawt.ai.audio

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class AudioHistoryStoreTest {
    private lateinit var root: File
    private lateinit var source: File
    private lateinit var store: AudioHistoryStore

    @Before fun setUp() {
        root = Files.createTempDirectory("sawt-history-test").toFile()
        source = File(root, "source.wav")
        writeWave(source)
        store = AudioHistoryStore(File(root, "history"))
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    @Test fun savesAndReloadsArabicMultilineText() {
        val text = "السَّلَامُ عَلَيْكُمْ\nهذا اختبار."
        val entry = store.add(source, "ar", "M1", "supertonic", 1.0f, 8, text, 100L)
        val loaded = AudioHistoryStore(File(root, "history")).list().single()
        assertEquals(entry.id, loaded.id)
        assertEquals(text, loaded.text)
        assertEquals("M1", loaded.voiceName)
        assertTrue((store.audioFile(entry)?.length() ?: 0L) >= 44L)
    }

    @Test fun deletesEntryAndAudioFile() {
        val entry = store.add(source, "ar", "F1", "supertonic", 1.0f, 6, "مَرْحَبًا")
        val file = store.audioFile(entry)!!
        assertTrue(store.delete(entry.id))
        assertFalse(file.exists())
        assertTrue(store.list().isEmpty())
    }

    @Test fun enforcesHistoryLimitAndDeletesOldest() {
        val limited = AudioHistoryStore(File(root, "small"), maxEntries = 2)
        val a = limited.add(source, "ar", "M1", "supertonic", 1.0f, 4, "الأول", 1L)
        limited.add(source, "ar", "M2", "supertonic", 1.0f, 4, "الثاني", 2L)
        limited.add(source, "ar", "M3", "supertonic", 1.0f, 4, "الثالث", 3L)
        assertNull(limited.audioFile(a))
        assertEquals(listOf("الثالث", "الثاني"), limited.list().map { it.text })
    }

    @Test fun ignoresCorruptedIndexLines() {
        store.add(source, "ar", "M1", "supertonic", 1.0f, 8, "صوت")
        File(root, "history/history.tsv").appendText("\nnot-a-valid-row")
        assertEquals(1, store.list().size)
    }

    private fun writeWave(file: File) {
        RandomAccessFile(file, "rw").use { out ->
            out.writeBytes("RIFF")
            out.writeInt(Integer.reverseBytes(36 + 256))
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeInt(Integer.reverseBytes(16))
            out.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            out.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            out.writeInt(Integer.reverseBytes(24_000))
            out.writeInt(Integer.reverseBytes(48_000))
            out.writeShort(java.lang.Short.reverseBytes(2.toShort()).toInt())
            out.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
            out.writeBytes("data")
            out.writeInt(Integer.reverseBytes(256))
            out.write(ByteArray(256))
        }
    }

    @Test fun prunesOldRecordingsWhenTheLibraryByteLimitIsReached() {
        val limited = AudioHistoryStore(File(root, "byte-limited"), maxEntries = 10, maxTotalBytes = 350L)
        val first = limited.add(source, "ar", "M1", "supertonic", 1.0f, 4, "الأقدم", 1L)
        val latest = limited.add(source, "ar", "M2", "supertonic", 1.0f, 4, "الأحدث", 2L)
        assertNull(limited.audioFile(first))
        assertNotNull(limited.audioFile(latest))
        assertEquals(listOf("الأحدث"), limited.list().map { it.text })
    }

    @Test fun rejectsOneRecordingLargerThanTheLibraryLimit() {
        val limited = AudioHistoryStore(File(root, "too-small"), maxEntries = 10, maxTotalBytes = 100L)
        try {
            limited.add(source, "ar", "M1", "supertonic", 1.0f, 4, "كبير")
            fail("Expected the oversized history item to be rejected")
        } catch (expected: java.io.IOException) {
            assertTrue(limited.list().isEmpty())
        }
    }
}
