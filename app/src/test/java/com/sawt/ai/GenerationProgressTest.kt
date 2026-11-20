package com.sawt.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationProgressTest {
    @Test
    fun formatsSecondsAndMinutesWithoutNegativeTime() {
        assertEquals("0 ث", GenerationProgress.formatDuration(-1L))
        assertEquals("5 ث", GenerationProgress.formatDuration(5_999L))
        assertEquals("1 د 0 ث", GenerationProgress.formatDuration(60_000L))
        assertEquals("2 د 5 ث", GenerationProgress.formatDuration(125_000L))
    }

    @Test
    fun estimatesTimeFromPositivePerCharacterRate() {
        assertEquals(12_500L, GenerationProgress.estimateMs(250f, 50))
        assertEquals(1_000L, GenerationProgress.estimateMs(0.1f, 10))
    }

    @Test
    fun rejectsInvalidOrMissingEstimateInputs() {
        assertEquals(0L, GenerationProgress.estimateMs(0f, 50))
        assertEquals(0L, GenerationProgress.estimateMs(-1f, 50))
        assertEquals(0L, GenerationProgress.estimateMs(Float.NaN, 50))
        assertEquals(0L, GenerationProgress.estimateMs(Float.POSITIVE_INFINITY, 50))
        assertEquals(0L, GenerationProgress.estimateMs(10f, 0))
    }

    @Test
    fun estimatesRemainingTimeFromCharactersNotYetGenerated() {
        assertEquals(5_000L, GenerationProgress.estimateRemainingMs(250f, 30, 50))
        assertEquals(0L, GenerationProgress.estimateRemainingMs(250f, 50, 50))
        assertEquals(0L, GenerationProgress.estimateRemainingMs(250f, 0, 0))
    }

    @Test
    fun observedRateRejectsInvalidProgressAndUsesElapsedPerCharacter() {
        assertEquals(100f, GenerationProgress.observedMsPerCharacter(5_000L, 50), 0.001f)
        assertEquals(0f, GenerationProgress.observedMsPerCharacter(0L, 50), 0.001f)
        assertEquals(0f, GenerationProgress.observedMsPerCharacter(5_000L, 0), 0.001f)
    }

    @Test
    fun rateKeyIsStableAndIncludesLanguageAndSpeed() {
        assertEquals(
            "generation_ms_per_char_ar_1.1",
            GenerationProgress.rateKey("ar", 1.1f)
        )
        assertEquals(
            "generation_ms_per_char_en_0.8",
            GenerationProgress.rateKey("en", 0.8f)
        )
    }
}
