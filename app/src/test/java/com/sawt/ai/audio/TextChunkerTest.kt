package com.sawt.ai.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {
    @Test fun splitPreservesArabicTextAndPunctuation() {
        val source = "مقدمة عربية، " + "هذا نص طويل للاختبار. ".repeat(30) + "النهاية؟"
        val chunks = TextChunker.split(source, 80)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 80 })
        assertEquals(source, chunks.joinToString(""))
    }

    @Test fun splitHandlesLongWordsWithoutDroppingText() {
        val source = "ك".repeat(501)
        val chunks = TextChunker.split(source, 100)
        assertEquals(6, chunks.size)
        assertTrue(chunks.all { it.length <= 100 })
        assertEquals(source, chunks.joinToString(""))
    }

    @Test fun shortAndEmptyTextAreHandled() {
        assertEquals(emptyList<String>(), TextChunker.split("", 180))
        assertEquals(listOf("مرحبا"), TextChunker.split("مرحبا", 180))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidChunkSize() {
        TextChunker.split("نص", 0)
    }
}
