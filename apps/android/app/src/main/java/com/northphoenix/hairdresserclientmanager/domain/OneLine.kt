package com.northphoenix.hairdresserclientmanager.domain

/**
 * Text that is stored and shown on one line: a name, a phone number, a Color Formula placement.
 * Mirrors `toOneLine` and the name rule of packages/api/src/input.ts, so that a field shows what
 * the server will store: a line break or a tab becomes one space together with the spaces around
 * it, and every other control character, which nobody can see, is removed.
 */
object OneLine {
    // Written with escapes: some of these characters are invisible in an editor.
    private val lineBreaks = Regex("[\\t\\n\\u000B\\u000C\\r\\u0085\\u2028\\u2029]+")

    /** The text as it is stored. */
    fun clean(text: String): String = typed(text).trim(::isSpace)

    /**
     * The text as a field holds it while it is typed into: [clean] without the trimming of its
     * ends, so that a space can be typed after a word. Text without a line break or a control
     * character, which is all a keyboard gives, is left as it is.
     */
    fun typed(text: String): String {
        if (text.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }) {
            return text
        }

        val lines = text.split(lineBreaks)

        return lines
            .mapIndexed { index, line ->
                val start = if (index == 0) line else line.trimStart(::isSpace)

                if (index == lines.lastIndex) start else start.trimEnd(::isSpace)
            }
            .filter(String::isNotEmpty)
            .joinToString(" ")
            .filterNot(Char::isISOControl)
    }

    /** A name needs a character that can be seen: spaces, control and zero-width characters alone are no name. */
    fun hasVisibleCharacter(text: String): Boolean {
        var index = 0

        while (index < text.length) {
            val point = text.codePointAt(index)

            if (Character.getType(point) !in invisible) {
                return true
            }

            index += Character.charCount(point)
        }

        return false
    }

    private val invisible = listOf(
        Character.CONTROL,
        Character.FORMAT,
        Character.SPACE_SEPARATOR,
        Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR,
    ).map(Byte::toInt)

    /** What the server trims: the spaces of every script, line breaks, tabs and the byte order mark. */
    private fun isSpace(char: Char): Boolean =
        char in '\t'..'\r' || char == '\uFEFF' || char == '\u2028' || char == '\u2029' || char.category == CharCategory.SPACE_SEPARATOR
}
