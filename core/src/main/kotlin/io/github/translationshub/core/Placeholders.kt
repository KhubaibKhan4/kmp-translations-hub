package io.github.translationshub.core

/** A format argument such as `%1$s`, `%d` or `%2$.1f`. */
data class Placeholder(val index: Int, val conversion: Char, val text: String)

object Placeholders {

    private val regex = Regex("""%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([sdfxXoeEgGc])""")

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

    /** Index → conversion, ignoring order and repeats: `%1$s … %2$d` matches `%2$d … %1$s`. */
    fun signature(text: String): Map<Int, Char> = find(text).associate { it.index to it.conversion.lowercaseChar() }

    private val tag = Regex("""<(/?)([A-Za-z][\w:.-]*)[^<>]*>""")

    /**
     * The markup tags in [text] (`b`, `/b`, `a`…), with repeats. Inline markup is protected and restored as it was, so
     * a translation never has a tag its source lacks; one that does came back as junk (`<string name="cq">`).
     */
    fun tags(text: String): List<String> = tag.findAll(text).map { it.groupValues[1] + it.groupValues[2] }.toList()

    /** Whether [translation] adds no tag that [source] doesn't have as many times. */
    fun addsNoMarkup(source: String, translation: String): Boolean {
        val allowed = tags(source).groupingBy { it }.eachCount()
        return tags(translation).groupingBy { it }.eachCount().all { (name, n) -> n <= (allowed[name] ?: 0) }
    }
}
