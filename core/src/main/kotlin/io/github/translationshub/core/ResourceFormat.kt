package io.github.translationshub.core

/**
 * Which resource system a `values…` folder belongs to. Both use the same `strings.xml` format; Android's resource
 * compiler is stricter about quotes, and a string can point to another one (`@string/app_name`).
 */
enum class ResourceFormat(val rootName: String) {
    /** Compose Multiplatform's `composeResources`. */
    COMPOSE("composeResources"),

    /** Android's `res`, in an Android app or a KMP module's `androidMain`. */
    ANDROID("res");

    companion object {
        fun ofRoot(folderName: String): ResourceFormat? = entries.firstOrNull { it.rootName == folderName }
    }
}

/** Android's rules for text in `strings.xml`, which fail the build rather than show something odd. */
object AndroidText {

    private val tag = Regex("""</?[A-Za-z][\w:.-]*(\s+[\w:.-]+\s*=\s*"[^"<]*")*\s*/?>""")
    private val reference = Regex("""^@\+?(\w+:)?string/[\w.]+$""")
    private val attribute = Regex("""^\?(\w+:)?(attr/)?[\w.]+$""")

    /** `@string/app_name` and the like: not text, so it isn't translated and its `@` stays as it is. */
    fun isReference(text: String) = reference.matches(text.trim())

    /**
     * A value made of references: a string that points to another, or an array with `@string/…` items (typically a
     * preference list next to values like `1080p`). The strings it points to are translated on their own.
     */
    fun isReferenceValue(value: ResourceValue) = when (value) {
        is TextValue -> isReference(value.text)
        is ArrayValue -> value.items.any(::isReference)
        is PluralsValue -> false
    }

    private val untranslatableRoot = Regex("""<resources\b[^>]*\btranslatable\s*=\s*"false"""")

    /**
     * A whole file that isn't translated, by Android's conventions: `<resources translatable="false">`, or a file named
     * `donottranslate.xml`. Not `tools:ignore="MissingTranslation"`: that only silences lint while translations are
     * incomplete (NewPipe's main strings.xml has it).
     */
    fun isUntranslatableFile(fileName: String, text: String) =
        fileName.startsWith("donottranslate") || untranslatableRoot.containsMatchIn(text)

    /**
     * Escapes what Android rejects: a `'` or `"` not already escaped (outside inline markup such as `<a href="…">`),
     * and a leading `@` or `?` that isn't a reference. Text already in Android form passes unchanged, so escaping
     * twice is safe; a string wrapped in double quotes, Android's other way to write it, is left alone.
     */
    fun escape(text: String): String {
        if (isReference(text) || attribute.matches(text.trim()) || isQuoted(text)) return text
        val out = StringBuilder(text.length + 4)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val markup = if (c == '<') tag.matchAt(text, i) else null
            when {
                markup != null -> { out.append(markup.value); i += markup.value.length; continue }
                c == '\\' && i + 1 < text.length -> { out.append(c).append(text[i + 1]); i += 2; continue }
                c == '\'' || c == '"' -> out.append('\\').append(c)
                (c == '@' || c == '?') && out.isBlank() -> out.append('\\').append(c)
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** The first `'` or `"` Android would reject, or null. */
    fun unescapedQuote(text: String): Char? {
        if (isQuoted(text)) return null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val markup = if (c == '<') tag.matchAt(text, i) else null
            when {
                markup != null -> { i += markup.value.length; continue }
                c == '\\' -> i++
                c == '\'' || c == '"' -> return c
            }
            i++
        }
        return null
    }

    fun escape(value: ResourceValue): ResourceValue = when (value) {
        is TextValue -> TextValue(escape(value.text))
        is PluralsValue -> PluralsValue(value.items.mapValues { escape(it.value) })
        is ArrayValue -> ArrayValue(value.items.map(::escape))
    }

    private fun isQuoted(text: String) = text.length >= 2 && text.first() == '"' && text.last() == '"' && !text.endsWith("\\\"")
}
