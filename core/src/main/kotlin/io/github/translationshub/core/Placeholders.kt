package io.github.translationshub.core

/** A format argument such as `%1$s`, `%d`, `%2$.1f`, or on iOS `%@` and `%lld`. */
data class Placeholder(val index: Int, val conversion: Char, val text: String)

object Placeholders {

    /**
     * Java's and Android's `%1$s`, `%d`, `%.1f`, and Apple's `%@`, `%lld`, `%ld`, `%u` (length modifiers and the
     * object conversion of String Catalogs). Without a capture group, for building other patterns. No space flag:
     * "50% sure" and "% of" are text, not placeholders.
     */
    const val PATTERN = """%(?:\d+\$)?[-#+0,(]*\d*(?:\.\d+)?(?:hh|h|ll|l|q|z|t|j|L)?[sdfxXoeEgGcaA@iuSC]"""

    private val regex = Regex("""%(?:(\d+)\$)?[-#+0,(]*\d*(?:\.\d+)?(?:hh|h|ll|l|q|z|t|j|L)?([sdfxXoeEgGcaA@iuSC])""")

    /**
     * The placeholders in [text], with plain `%s`/`%d` numbered by position the way `String.format` reads them.
     * `%%` is a literal percent sign and is skipped.
     */
    fun find(text: String): List<Placeholder> {
        val result = mutableListOf<Placeholder>()
        var next = 1
        var i = 0
        while (i < text.length) {
            if (text[i] == '%' && text.getOrNull(i + 1) == '%') {
                i += 2
                continue
            }
            val match = regex.matchAt(text, i)
            if (match != null) {
                val explicit = match.groupValues[1].toIntOrNull()
                result += Placeholder(explicit ?: next++, match.groupValues[2][0], match.value)
                i = match.range.last + 1
            } else {
                i++
            }
        }
        return result
    }

    /**
     * Index → conversion, ignoring order and repeats: `%1$s … %2$d` matches `%2$d … %1$s`. `%i` and `%u` count as
     * `%d`, and `%ld`/`%lld` as `%d`: they print the same number.
     */
    fun signature(text: String): Map<Int, Char> = find(text).associate { it.index to normalized(it.conversion) }

    private fun normalized(conversion: Char) = when (val c = conversion.lowercaseChar()) {
        'i', 'u' -> 'd'
        else -> c
    }

    private val tag = Regex("""<(/?)([A-Za-z][\w:.-]*)[^<>]*>""")

    /**
     * The markup tags in [text] (`b`, `/b`, `a`…), with repeats. Inline markup is protected and restored as it was, so
     * a translation never has a tag its source lacks; one that does came back as junk (`<string name="cq">`).
     */
    fun tags(text: String): List<String> = tag.findAll(text).map { it.groupValues[1] + it.groupValues[2] }.toList()

    /**
     * [translation] with [source]'s leading and trailing whitespace. Services often trim it, but a string like
     * " • %@" is shown after other text and needs its space.
     */
    fun keepEdgeWhitespace(source: String, translation: String): String {
        val leading = source.takeWhile(Char::isWhitespace)
        val trailing = source.takeLastWhile(Char::isWhitespace).takeIf { it.length < source.length }.orEmpty()
        return leading + translation.trim() + trailing
    }

    /** Whether [translation] adds no tag that [source] doesn't have as many times. */
    fun addsNoMarkup(source: String, translation: String): Boolean {
        val allowed = tags(source).groupingBy { it }.eachCount()
        return tags(translation).groupingBy { it }.eachCount().all { (name, n) -> n <= (allowed[name] ?: 0) }
    }
}
