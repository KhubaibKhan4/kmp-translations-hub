package io.github.translationshub.core

sealed interface Problem {
    val key: ResourceKey
    val locale: Locale?
    val message: String

    /** The key has no translation in [locale]. */
    data class Missing(override val key: ResourceKey, override val locale: Locale) : Problem {
        override val message get() = "Missing ${locale.tag} translation"
    }

    /** The translation's `%1$s`, `%d`… differ from the source's: wrong text at best, a crash at worst. */
    data class PlaceholderMismatch(
        override val key: ResourceKey,
        override val locale: Locale,
        val expected: Map<Int, Char>,
        val actual: Map<Int, Char>,
    ) : Problem {
        override val message
            get() = "Placeholders ${describe(actual)} don't match the default text's ${describe(expected)}"

        private fun describe(signature: Map<Int, Char>) =
            if (signature.isEmpty()) "(none)" else signature.toSortedMap().entries.joinToString(" ") { "%${it.key}\$${it.value}" }
    }

    /** A `<plurals>` lacks a category the language needs, e.g. `few` in Polish. */
    data class MissingPluralCategories(
        override val key: ResourceKey,
        override val locale: Locale?,
        val missing: Set<PluralCategory>,
    ) : Problem {
        override val message get() = "Missing plural forms: ${missing.joinToString { it.xmlName }}"
    }

    /** A string-array has a different number of items than the source's. */
    data class ArraySizeMismatch(override val key: ResourceKey, override val locale: Locale, val expected: Int, val actual: Int) : Problem {
        override val message get() = "$actual items, the default has $expected"
    }

    /** A backslash escape Compose doesn't turn into a character, so it shows up as written. */
    data class UnknownEscape(override val key: ResourceKey, override val locale: Locale?, val escape: String) : Problem {
        override val message get() = "Unknown escape $escape: it will show as written (use \\n, \\t, \\', \\\", \\\\ or \\uXXXX)"
    }

    /** The translation is the same as the default text: probably not translated yet. */
    data class Untranslated(override val key: ResourceKey, override val locale: Locale) : Problem {
        override val message get() = "Same as the default text: not translated?"
    }

    /** Much longer than the default text, so it may not fit where the default does. */
    data class TooLong(override val key: ResourceKey, override val locale: Locale, val length: Int, val sourceLength: Int) : Problem {
        override val message get() = "$length characters, the default has $sourceLength: may not fit"
    }

    /** No Kotlin code uses the key's `Res.string`/`Res.plurals`/`Res.array` accessor. */
    data class Unused(override val key: ResourceKey) : Problem {
        override val locale: Locale? get() = null
        override val message get() = "Not used in Kotlin code"
    }

    /**
     * A quote Android doesn't take as text: an unescaped `'` fails the build; an unescaped `"` is silently dropped, so
     * the text shows without it.
     */
    data class UnescapedQuote(override val key: ResourceKey, override val locale: Locale?, val quote: Char) : Problem {
        val failsBuild get() = quote == '\''
        override val message get() = if (failsBuild) "Android fails to build on an unescaped ': write \\', or wrap the whole text in double quotes"
        else "Android drops an unescaped \", so it won't show: write \\\" to keep it"
    }

    /** The key exists in a translation but not in the source. */
    data class NotInSource(override val key: ResourceKey, override val locale: Locale) : Problem {
        override val message get() = "Not in the default values: the source key was renamed or removed"
    }
}

/**
 * Which checks run. The free tier gets missing keys, placeholders, array sizes and keys left behind; Pro adds plural
 * forms per language, escapes, untranslated text, length and (with [usedKeys]) unused keys.
 */
data class CheckOptions(
    val perLanguagePlurals: Boolean = true,
    val extended: Boolean = true,
    /** Keys some Kotlin code uses; null skips the unused-key check. */
    val usedKeys: Set<ResourceKey>? = null,
) {
    companion object {
        val FREE = CheckOptions(perLanguagePlurals = false, extended = false)
    }
}

object Checks {

    fun run(set: TranslationSet, options: CheckOptions = CheckOptions()): List<Problem> = buildList {
        for (key in set.keys) {
            val source = set.source(key)
            if (source == null) {
                set.locales.filter { set.translation(key, it) != null }.forEach { add(Problem.NotInSource(key, it)) }
                continue
            }
            missingPlurals(key, null, source.value, options)?.let(::add)
            if (set.format == ResourceFormat.ANDROID) unescapedQuote(key, null, source.value)?.let(::add)
            if (options.extended) unknownEscape(key, null, source.value)?.let(::add)
            if (options.usedKeys != null && key !in options.usedKeys) add(Problem.Unused(key))
            if (!source.translatable) continue

            for (locale in set.locales) {
                val translation = set.translation(key, locale)
                if (translation == null) {
                    add(Problem.Missing(key, locale))
                    continue
                }
                placeholderMismatch(key, locale, source.value, translation.value)?.let(::add)
                missingPlurals(key, locale, translation.value, options)?.let(::add)
                if (set.format == ResourceFormat.ANDROID) unescapedQuote(key, locale, translation.value)?.let(::add)
                val s = source.value
                val t = translation.value
                if (s is ArrayValue && t is ArrayValue && s.items.size != t.items.size) {
                    add(Problem.ArraySizeMismatch(key, locale, s.items.size, t.items.size))
                }
                if (options.extended) {
                    unknownEscape(key, locale, t)?.let(::add)
                    untranslated(key, locale, s, t)?.let(::add)
                    tooLong(key, locale, s, t)?.let(::add)
                }
            }
        }
    }

    /**
     * Placeholders are compared over the whole value: plural items often drop the number (`one` → "One file"),
     * so a `<plurals>` only has to use the same arguments somewhere.
     */
    fun placeholderMismatch(key: ResourceKey, locale: Locale, source: ResourceValue, translation: ResourceValue): Problem? {
        val expected = signature(source)
        val actual = signature(translation)
        val ok = when (source) {
            is PluralsValue -> actual.all { (index, conversion) -> expected[index] == conversion }
            else -> expected == actual
        }
        return if (ok) null else Problem.PlaceholderMismatch(key, locale, expected, actual)
    }

    private fun signature(value: ResourceValue): Map<Int, Char> =
        value.texts.fold(emptyMap()) { acc, text -> acc + Placeholders.signature(text) }

    private fun missingPlurals(key: ResourceKey, locale: Locale?, value: ResourceValue, options: CheckOptions): Problem? {
        if (value !is PluralsValue) return null
        // The source folder's language isn't known; `other` is the one form every language needs.
        val needed = if (locale == null || !options.perLanguagePlurals) setOf(PluralCategory.OTHER) else PluralRules.categories(locale)
        val missing = needed - value.items.keys
        return if (missing.isEmpty()) null else Problem.MissingPluralCategories(key, locale, missing)
    }

    private fun unescapedQuote(key: ResourceKey, locale: Locale?, value: ResourceValue): Problem? =
        value.texts.firstNotNullOfOrNull(AndroidText::unescapedQuote)?.let { Problem.UnescapedQuote(key, locale, it) }

    /** Compose turns `\n`, `\t`, `\'`, `\"`, `\\` and `\uXXXX` into characters; anything else stays as written. */
    private val escape = Regex("""\\(u[0-9a-fA-F]{4}|.)""")
    private val knownEscapes = setOf("n", "t", "'", "\"", "\\", "@", "?")

    private fun unknownEscape(key: ResourceKey, locale: Locale?, value: ResourceValue): Problem? {
        for (text in value.texts) for (match in escape.findAll(text)) {
            val what = match.groupValues[1]
            if (what !in knownEscapes && what.length != 5) return Problem.UnknownEscape(key, locale, match.value)
        }
        return null
    }

    /** Only text with words in it: "OK", "%1$s" or "—" are often the same in every language. */
    private fun untranslated(key: ResourceKey, locale: Locale, source: ResourceValue, translation: ResourceValue): Problem? {
        if (source != translation) return null
        val words = source.texts.joinToString(" ").replace(Regex("%(\\d+\\$)?[a-z]"), "").split(Regex("[^\\p{L}\\p{N}]+")).filter { w -> w.count(Char::isLetter) >= 2 }
        return if (words.size >= 2) Problem.Untranslated(key, locale) else null
    }

    private fun tooLong(key: ResourceKey, locale: Locale, source: ResourceValue, translation: ResourceValue): Problem? {
        if (source !is TextValue || translation !is TextValue) return null
        val s = source.text.length
        val t = translation.text.length
        return if (s >= 4 && t > s * 5 / 2 && t - s > 10) Problem.TooLong(key, locale, t, s) else null
    }
}
