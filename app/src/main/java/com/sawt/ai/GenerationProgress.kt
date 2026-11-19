package com.sawt.ai

import java.util.Locale

/**
 * Pure helpers for the progress panel. Kept free of Android APIs so they can
 * be exercised by local JVM unit tests.
 */
internal object GenerationProgress {
    fun formatDuration(milliseconds: Long): String {
        val seconds = (milliseconds / 1_000L).coerceAtLeast(0L)
        val minutes = seconds / 60L
        val remainder = seconds % 60L
        return if (minutes > 0L) "${minutes} د ${remainder} ث" else "${seconds} ث"
    }

    fun rateKey(language: String, speed: Float): String =
        "generation_ms_per_char_${language}_${String.format(Locale.US, "%.1f", speed)}"

    fun estimateMs(averageMsPerCharacter: Float, characterCount: Int): Long {
        if (!averageMsPerCharacter.isFinite() || averageMsPerCharacter <= 0f || characterCount <= 0) {
            return 0L
        }
        val estimate = averageMsPerCharacter.toDouble() * characterCount.toDouble()
        if (!estimate.isFinite() || estimate > Long.MAX_VALUE.toDouble()) return 0L
        return estimate.toLong().coerceAtLeast(1_000L)
    }
}
