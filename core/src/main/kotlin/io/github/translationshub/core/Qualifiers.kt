package io.github.translationshub.core

/**
 * A language, with an optional script and region, as written in a `values-…` folder name:
 * `ur`, `pt-rBR` or `b+sr+Latn`.
 */
data class Locale(val language: String, val script: String? = null, val region: String? = null) : Comparable<Locale> {

    /** BCP 47 form, e.g. `pt-BR`, `sr-Latn`: what translation services and the devices expect. */
    val tag: String get() = listOfNotNull(language, script, region).joinToString("-")

    /** The form used in folder names: `pt-rBR`, or `b+sr+Latn` when there is a script or a numeric region. */
    val qualifier: String
        get() = if (script != null || region?.all(Char::isDigit) == true) listOfNotNull("b", language, script, region).joinToString("+")
        else listOfNotNull(language, region?.let { "r$it" }).joinToString("-")

    override fun compareTo(other: Locale): Int = tag.compareTo(other.tag)

    override fun toString(): String = tag

    companion object {
        /** Parses a BCP 47 tag such as `pt-BR` or `sr-Latn-RS`; null if it isn't one. */
        fun fromTag(tag: String): Locale? {
            val parts = tag.split('-', '_')
            val language = parts.first().lowercase().takeIf(::isLanguage) ?: return null
            var script: String? = null
            var region: String? = null
            for (part in parts.drop(1)) when {
                script == null && region == null && part.length == 4 && part.all(Char::isLetter) ->
                    script = part.lowercase().replaceFirstChar(Char::uppercaseChar)
                region == null && isRegion(part) -> region = part.uppercase()
                else -> return null
            }
            return Locale(language, script, region)
        }

        internal fun isLanguage(s: String) = s.length in 2..3 && s.all { it in 'a'..'z' || it in 'A'..'Z' }
        internal fun isRegion(s: String) =
            (s.length == 2 && s.all(Char::isLetter)) || (s.length == 3 && s.all(Char::isDigit))
    }
}

/**
 * The qualifiers of a `values` folder. [locale] is null for the default `values` folder; [other] keeps the
 * non-language qualifiers (`dark`, `night`, `xhdpi`…) so a `values-ur-dark` folder is a separate variant of the
 * Urdu column rather than a second Urdu column.
 */
data class FolderQualifiers(val locale: Locale?, val other: List<String> = emptyList()) {

    val folderName: String
        get() = (listOf("values") + listOfNotNull(locale?.qualifier) + other).joinToString("-")

    companion object {
        /** Parses `values`, `values-ur`, `values-pt-rBR`, `values-b+sr+Latn`, `values-ur-dark`…; null for any other folder. */
        fun parse(folderName: String): FolderQualifiers? {
            if (folderName == "values") return FolderQualifiers(null)
            if (!folderName.startsWith("values-")) return null
            val parts = folderName.removePrefix("values-").split('-').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null

            var index = 0
            var locale: Locale? = null
            val first = parts[0]
            if (first.startsWith("b+")) {
                // BCP 47 form: b+language(+script)(+region)
                locale = Locale.fromTag(first.removePrefix("b+").replace('+', '-')) ?: return null
                index = 1
            } else if (Locale.isLanguage(first) && first !in NON_LANGUAGE_QUALIFIERS) {
                val regionPart = parts.getOrNull(1)
                val region = regionPart?.takeIf { it.length == 3 && it[0] == 'r' && Locale.isRegion(it.substring(1)) }
                locale = Locale(first.lowercase(), region = region?.substring(1)?.uppercase())
                index = if (region != null) 2 else 1
            }
            return FolderQualifiers(locale, parts.drop(index))
        }

        /** Two- and three-letter qualifiers that are not languages. */
        private val NON_LANGUAGE_QUALIFIERS = setOf("dpi", "car", "sw", "w", "h")
    }
}
