package com.sawt.ai.audio

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TashkeelTextProcessorTest {
    @Test
    fun detectsArabicTextButNotEnglishText() {
        assertTrue(TashkeelTextProcessor.containsArabic("هذا نص عربي"))
        assertFalse(TashkeelTextProcessor.containsArabic("English only"))
    }

    @Test
    fun stripsHarakatWithoutRemovingLettersOrPunctuation() {
        assertEquals(
            "محمد، هذا كتاب",
            TashkeelTextProcessor.stripDiacritics("مُحَمَّدٌ، هَذَا كِتَابٌ")
        )
    }

    @Test
    fun splittingPreservesAllCharactersAndKeepsEveryChunkWithinLimit() {
        val source = ("هذه جملة عربية طويلة. ").repeat(80)
        val chunks = TashkeelTextProcessor.split(source, 180)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 180 })
        assertEquals(source, chunks.joinToString(""))
    }

    @Test
    fun mapsCursorAfterAddedCombiningMarks() {
        val source = "سلام"
        val diacritized = "سَلَامٌ"
        assertEquals(
            TextRange(4, 4),
            TashkeelTextProcessor.mapSelection(source, diacritized, TextRange(2, 2))
        )
    }

    @Test
    fun mapsSelectionToEndWhenCursorWasAtEnd() {
        val source = "مرحبا"
        val diacritized = "مَرْحَبًا"
        assertEquals(
            TextRange(diacritized.length, diacritized.length),
            TashkeelTextProcessor.mapSelection(
                source,
                diacritized,
                TextRange(source.length, source.length)
            )
        )
    }
}
