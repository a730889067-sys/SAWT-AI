package com.sawt.ai.audio

internal object TextChunker {
    /** Splits while preserving every character and original ordering. */
    fun split(text: String, maxCharacters: Int): List<String> {
        require(maxCharacters > 0) { "maxCharacters must be positive" }
        if (text.isEmpty()) return emptyList()
        if (text.length <= maxCharacters) return listOf(text)

        val sentenceBreaks = setOf('،', '؛', '.', '!', '?', '؟', '\n', '\r')
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
            for (index in hardEnd - 1 downTo minimumBoundary) {
                if (text[index] in sentenceBreaks) {
                    boundary = index + 1
                    break
                }
            }
            if (boundary < 0) {
                for (index in hardEnd - 1 downTo minimumBoundary) {
                    if (text[index].isWhitespace()) {
                        boundary = index + 1
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
}
