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
            get() = "Placeholders ${describe(actual)} don't match the source's ${describe(expected)}"

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
        override val message get() = "$actual items, the source has $expected"
    }

    /** The key exists in a translation but not in the source. */
    data class NotInSource(override val key: ResourceKey, override val locale: Locale) : Problem {
        override val message get() = "Not in the default values: the source key was renamed or removed"
    }
}

object Checks {

    fun run(set: TranslationSet): List<Problem> = buildList {
        for (key in set.keys) {
            val source = set.source(key)
            if (source == null) {
                set.locales.filter { set.translation(key, it) != null }.forEach { add(Problem.NotInSource(key, it)) }
                continue
            }
            missingPlurals(key, null, source.value)?.let(::add)
            if (!source.translatable) continue

            for (locale in set.locales) {
                val translation = set.translation(key, locale)
                if (translation == null) {
                    add(Problem.Missing(key, locale))
                    continue
                }
                placeholderMismatch(key, locale, source.value, translation.value)?.let(::add)
                missingPlurals(key, locale, translation.value)?.let(::add)
                val s = source.value
                val t = translation.value
                if (s is ArrayValue && t is ArrayValue && s.items.size != t.items.size) {
                    add(Problem.ArraySizeMismatch(key, locale, s.items.size, t.items.size))
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

    private fun missingPlurals(key: ResourceKey, locale: Locale?, value: ResourceValue): Problem? {
        if (value !is PluralsValue) return null
        // The source folder's language isn't known; `other` is the one form every language needs.
        val needed = if (locale == null) setOf(PluralCategory.OTHER) else PluralRules.categories(locale)
        val missing = needed - value.items.keys
        return if (missing.isEmpty()) null else Problem.MissingPluralCategories(key, locale, missing)
    }
}
