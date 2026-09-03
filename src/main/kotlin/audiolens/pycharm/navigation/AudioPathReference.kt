package audiolens.pycharm.navigation

import audiolens.pycharm.editor.AudioLensFileType

data class AudioPathReference(
    val startOffset: Int,
    val endOffset: Int,
    val pathText: String,
    val arkOffset: Long? = null,
)

object AudioPathReferenceParser {
    private val arkWithOffset = Regex("^(.*\\.ark):(\\d+)$", RegexOption.IGNORE_CASE)

    fun findAt(text: CharSequence, offset: Int): AudioPathReference? {
        if (text.isEmpty() || offset !in 0..text.length) return null
        val safeOffset = offset.coerceAtMost(text.lastIndex)
        val lineStart = findLineStart(text, safeOffset)
        val lineEnd = findLineEnd(text, safeOffset)

        quotedSpan(text, safeOffset, lineStart, lineEnd)?.let { (start, end) ->
            parseSpan(text, start, end)?.let { return it }
        }

        var start = safeOffset
        while (start > lineStart && !isTokenBoundary(text[start - 1])) start--
        var end = safeOffset
        while (end < lineEnd && !isTokenBoundary(text[end])) end++
        return parseSpan(text, start, end)
    }

    fun parse(pathText: String): AudioPathReference? = parseSpan(pathText, 0, pathText.length)

    private fun quotedSpan(text: CharSequence, offset: Int, lineStart: Int, lineEnd: Int): Pair<Int, Int>? {
        for (quote in charArrayOf('"', '\'', '`')) {
            var left = offset.coerceAtMost(lineEnd - 1)
            while (left >= lineStart) {
                if (text[left] == quote && !isEscaped(text, left)) break
                left--
            }
            if (left < lineStart) continue
            var right = maxOf(offset, left + 1)
            while (right < lineEnd) {
                if (text[right] == quote && !isEscaped(text, right)) break
                right++
            }
            if (right < lineEnd && offset in (left + 1)..right) return (left + 1) to right
        }
        return null
    }

    private fun parseSpan(text: CharSequence, initialStart: Int, initialEnd: Int): AudioPathReference? {
        var start = initialStart
        var end = initialEnd
        while (start < end && text[start].isWhitespace()) start++
        while (start < end && text[end - 1].isWhitespace()) end--
        while (start < end && text[start] in LEADING_PUNCTUATION) start++
        while (start < end && text[end - 1] in TRAILING_PUNCTUATION) end--

        val assignment = (start until end).lastOrNull { text[it] == '=' }
        if (assignment != null) {
            start = assignment + 1
            while (start < end && text[start] in LEADING_PUNCTUATION) start++
        }
        if (start >= end) return null

        val raw = text.subSequence(start, end).toString().trim()
        val match = arkWithOffset.matchEntire(raw)
        if (match != null) {
            val parsedOffset = match.groupValues[2].toLongOrNull() ?: return null
            return AudioPathReference(start, end, match.groupValues[1], parsedOffset)
        }
        val extension = raw.substringAfterLast('.', "").lowercase()
        if (extension !in AudioLensFileType.SUPPORTED_EXTENSIONS) return null
        return AudioPathReference(start, end, raw)
    }

    private fun findLineStart(text: CharSequence, offset: Int): Int {
        var cursor = offset
        while (cursor > 0 && text[cursor - 1] != '\n' && text[cursor - 1] != '\r') cursor--
        return cursor
    }

    private fun findLineEnd(text: CharSequence, offset: Int): Int {
        var cursor = offset
        while (cursor < text.length && text[cursor] != '\n' && text[cursor] != '\r') cursor++
        return cursor
    }

    private fun isEscaped(text: CharSequence, index: Int): Boolean {
        var backslashes = 0
        var cursor = index - 1
        while (cursor >= 0 && text[cursor] == '\\') {
            backslashes++
            cursor--
        }
        return backslashes % 2 == 1
    }

    private fun isTokenBoundary(character: Char): Boolean =
        character.isWhitespace() || character == '"' || character == '\'' || character == '`' || character == '<' || character == '>'

    private val LEADING_PUNCTUATION = setOf('(', '[', '{', ',', ';')
    private val TRAILING_PUNCTUATION = setOf(')', ']', '}', ',', ';')
}
