package io.github.translationshub.core

import io.github.translationshub.core.PluralCategory.FEW
import io.github.translationshub.core.PluralCategory.MANY
import io.github.translationshub.core.PluralCategory.ONE
import io.github.translationshub.core.PluralCategory.OTHER
import io.github.translationshub.core.PluralCategory.TWO
import io.github.translationshub.core.PluralCategory.ZERO

/**
 * The plural categories each language uses for cardinal numbers (CLDR 46). The editor shows only these for a
 * locale, and the checks flag the ones a `<plurals>` is missing.
 */
object PluralRules {

    private val OTHER_ONLY = setOf(OTHER)
    private val ONE_OTHER = setOf(ONE, OTHER)
    private val ONE_MANY_OTHER = setOf(ONE, MANY, OTHER)
    private val ONE_FEW_MANY_OTHER = setOf(ONE, FEW, MANY, OTHER)
    private val ONE_FEW_OTHER = setOf(ONE, FEW, OTHER)
    private val ALL = PluralCategory.entries.toSet()

    private val byLanguage: Map<String, Set<PluralCategory>> = buildMap {
        listOf("ja", "zh", "ko", "th", "vi", "id", "ms", "my", "lo", "km", "yue", "jv", "su", "yo", "ig", "bo", "dz")
            .forEach { put(it, OTHER_ONLY) }
        listOf(
            "en", "de", "nl", "sv", "da", "nb", "no", "nn", "fi", "et", "el", "hu", "tr", "az", "ka", "kk", "ky",
            "uz", "mn", "sq", "bg", "mk", "is", "ur", "hi", "bn", "fa", "ps", "pa", "gu", "mr", "ne", "si", "ta",
            "te", "kn", "ml", "or", "as", "sw", "am", "zu", "xh", "af", "ha", "so", "eu", "gl", "ast", "fy", "lb",
            "fil", "tl", "hy", "ug", "tk", "sd", "ku", "ckb",
        ).forEach { put(it, ONE_OTHER) }
        listOf("fr", "es", "it", "pt", "ca").forEach { put(it, ONE_MANY_OTHER) }
        listOf("ru", "uk", "be", "pl", "cs", "sk", "lt").forEach { put(it, ONE_FEW_MANY_OTHER) }
        listOf("hr", "sr", "bs", "ro", "mo").forEach { put(it, ONE_FEW_OTHER) }
        put("lv", setOf(ZERO, ONE, OTHER))
        put("sl", setOf(ONE, TWO, FEW, OTHER))
        put("he", setOf(ONE, TWO, OTHER))
        put("iw", setOf(ONE, TWO, OTHER))
        put("ga", setOf(ONE, TWO, FEW, MANY, OTHER))
        put("gd", setOf(ONE, TWO, FEW, OTHER))
        put("mt", setOf(ONE, TWO, FEW, MANY, OTHER))
        put("ar", ALL)
        put("cy", ALL)
    }

    /** The categories [locale] uses; `one` + `other` for a language this table doesn't know. */
    fun categories(locale: Locale?): Set<PluralCategory> = locale?.let { byLanguage[it.language] } ?: ONE_OTHER

    fun isKnown(locale: Locale): Boolean = locale.language in byLanguage
}
