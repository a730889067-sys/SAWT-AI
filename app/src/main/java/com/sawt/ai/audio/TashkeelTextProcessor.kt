package com.sawt.ai.audio

import androidx.compose.ui.text.TextRange

/**
 * Splits Arabic text into sentence-aware pieces accepted by Ichkil (max 1024 chars)
 * and maps editor selections after combining harakat have been inserted.
 */
internal object TashkeelTextProcessor {
    const val MAX_CHUNK_CHARACTERS = 900

    private val marks = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
    private val zeroWidth = Regex("[\\u200B-\\u200F\\uFEFF]")

    fun containsArabic(text: String): Boolean =
        text.any { it in '\\u0621'..'\\u063A' || it in '\\u0641'..'\\u064A' }

    fun stripDiacritics(text: String): String =
        zeroWidth.replace(marks.replace(text, ""), "").replace("\\u0640", "")

    fun split(text: String, maxCharacters: Int = MAX_CHUNK_CHARACTERS): List<String> {
        require(maxCharacters > 0) { "maxCharacters must be positive" }
        if (text.isEmpty()) return emptyList()
        if (text.length <= maxCharacters) return listOf(text)

        val boundaries = setOf('،', '؛', '.', '!', '?', '؟', '\\n', '\\r')
        val chunks = ArrayList<String>((text.length / maxCharacters) + 1)
        var start = 0
        while (start < text.length) {
            val hardEnd = minOf(start + maxCharacters, text.length)
            if (hardEnd == text.length) {
                chunks.add(text.substring(start))
                break
            }
            val minimumBoundary = start + (maxCharacters / 2).coerceAtLeast(1)
            var boundary = -1
            for (i in hardEnd - 1 downTo minimumBoundary) {
                if (text[i] in boundaries) {
                    boundary = i + 1
                    break
                }
            }
            if (boundary < 0) {
                for (i in hardEnd - 1 downTo minimumBoundary) {
                    if (text[i].isWhitespace()) {
                        boundary = i + 1
                        break
                    }
                }
            }
            if (boundary <= start) boundary = hardEnd
            chunks.add(text.substring(start, boundary))
            start = boundary
        }
        return chunks
    }

    fun mapSelection(source: String, result: String, selection: TextRange): TextRange =
        TextRange(
            mapOffset(source, result, selection.start),
            mapOffset(source, result, selection.end)
        )

    fun mapOffset(source: String, result: String, sourceOffset: Int): Int {
        val safeOffset = sourceOffset.coerceIn(0, source.length)
        val prefix = source.substring(0, safeOffset)
        val targetBaseCharacters = countBaseCharacters(prefix)
        if (targetBaseCharacters == 0) return 0

        var offset = 0
        var seenBaseCharacters = 0
        while (offset < result.length) {
            val codePoint = result.codePointAt(offset)
            offset += Character.charCount(codePoint)
            if (!isMarkOrFormatting(codePoint)) {
                seenBaseCharacters++
                if (seenBaseCharacters >= targetBaseCharacters) {
                    while (offset < result.length) {
                        val next = result.codePointAt(offset)
                        if (!isArabicMark(next)) break
                        offset += Character.charCount(next)
                    }
                    return offset.coerceAtMost(result.length)
                }
            }
        }
        return result.length
    }

    private fun countBaseCharacters(text: String): Int {
        var count = 0
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            offset += Character.charCount(codePoint)
            if (!isMarkOrFormatting(codePoint)) count++
        }
        return count
    }

    private fun isMarkOrFormatting(codePoint: Int): Boolean =
        isArabicMark(codePoint) ||
            codePoint == 0x0640 ||
            codePoint in 0x200B..0x200F ||
            codePoint == 0xFEFF

    private fun isArabicMark(codePoint: Int): Boolean =
        codePoint in 0x0610..0x061A ||
            codePoint in 0x064B..0x065F ||
            codePoint == 0x0670 ||
            codePoint in 0x06D6..0x06ED
}
