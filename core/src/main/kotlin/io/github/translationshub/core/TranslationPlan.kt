package io.github.translationshub.core

/**
 * Keeps what a translation service must not touch (`%1$s`, `%d`, inline markup such as `<b>`, Compose escapes such
 * as `\n` and `\'`) out of its reach: each is swapped for a token before sending and swapped back after. A result
 * that lost, duplicated or invented a token is rejected rather than saved.
 */
object Protection {

    enum class Style {
        /** `<x id="0"/>`: for services with XML/HTML tag handling (DeepL, Google), which keep tags in place. */
        XML_TAGS,

        /** `⟦0⟧`: for plain-text services (Apple), which leave unusual brackets alone. */
        BRACKETS,

        /**
         * `%1$s` and `%d` sent as they are; only markup and escapes become `⟦0⟧`. For translation memories
         * (MyMemory) built from software strings: they keep real placeholders but drop or rewrite made-up tokens.
         * The placeholders are still checked against the default text afterwards.
         */
        RAW_PLACEHOLDERS,
    }

    private val placeholder = Regex(Placeholders.PATTERN)

    data class Protected(val text: String, val tokens: List<String>)

    private val protectedParts = Regex(Placeholders.PATTERN + """|</?[A-Za-z][\w:.-]*(?:\s+[\w:.-]+\s*=\s*"[^"<]*")*\s*/?>|\\(?:u[0-9a-fA-F]{4}|.)""")

    fun protect(text: String, style: Style): Protected {
        val tokens = mutableListOf<String>()
        val masked = protectedParts.replace(text) { match ->
            if (style == Style.RAW_PLACEHOLDERS && placeholder.matches(match.value)) return@replace match.value
            tokens += match.value
            token(tokens.size - 1, style)
        }
        // XML-tag mode sends the rest as XML text, so its own & and < must be escaped.
        val body = if (style == Style.XML_TAGS) escapeOutsideTokens(masked) else masked
        return Protected(body, tokens)
    }

    /** The translation with the originals put back, or null if the tokens didn't come back exactly once each. */
    fun restore(translated: String, protected: Protected, style: Style): String? {
        val pattern = when (style) {
            Style.XML_TAGS -> Regex("""<x\s+id\s*=\s*"(\d+)"\s*/>""")
            Style.BRACKETS, Style.RAW_PLACEHOLDERS -> Regex("""⟦\s*(\d+)\s*⟧""")
        }
        val seen = mutableListOf<Int>()
        var restored = pattern.replace(translated) { match ->
            val index = match.groupValues[1].toInt()
            seen += index
            protected.tokens.getOrNull(index) ?: match.value
        }
        if (seen.sorted() != protected.tokens.indices.toList()) return null
        if (style == Style.XML_TAGS) restored = unescapeXml(restored)
        return restored
    }

    private fun token(index: Int, style: Style) = when (style) {
        Style.XML_TAGS -> "<x id=\"$index\"/>"
        Style.BRACKETS, Style.RAW_PLACEHOLDERS -> "⟦$index⟧"
    }

    private fun escapeOutsideTokens(text: String): String {
        val token = Regex("""<x id="\d+"/>""")
        val out = StringBuilder()
        var last = 0
        for (match in token.findAll(text)) {
            out.append(escapeXml(text.substring(last, match.range.first))).append(match.value)
            last = match.range.last + 1
        }
        out.append(escapeXml(text.substring(last)))
        return out.toString()
    }

    private fun escapeXml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun unescapeXml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
}

/**
 * What "Translate changes" sends for one locale: one segment per string, plural form the language needs, and array
 * item, with the key's comment as context. [assemble] turns the translated segments back into values.
 */
class TranslationPlan(val locale: Locale, val segments: List<Segment>) {

    data class Segment(val key: ResourceKey, val part: Part, val text: String, val context: String?)

    sealed interface Part {
        data object Whole : Part
        data class Plural(val category: PluralCategory) : Part
        data class Item(val index: Int) : Part
    }

    val keys: Set<ResourceKey> get() = segments.mapTo(LinkedHashSet()) { it.key }

    /**
     * Values from translated segments (by index in [segments]); a key is left out if any of its segments failed
     * (null), so a half-translated plural is never written.
     */
    fun assemble(results: List<String?>): Map<ResourceKey, ResourceValue> {
        require(results.size == segments.size) { "${results.size} results for ${segments.size} segments" }
        val byKey = segments.indices.groupBy { segments[it].key }
        return buildMap {
            for ((key, indices) in byKey) {
                if (indices.any { results[it] == null }) continue
                val value: ResourceValue = when (key.kind) {
                    ResourceKind.STRING -> TextValue(results[indices.single()]!!)
                    ResourceKind.PLURALS -> PluralsValue(indices.associate { (segments[it].part as Part.Plural).category to results[it]!! })
                    ResourceKind.ARRAY -> ArrayValue(indices.sortedBy { (segments[it].part as Part.Item).index }.map { results[it]!! })
                }
                put(key, value)
            }
        }
    }

    companion object {
        /**
         * The plan for [keys] in [locale]. Each plural form the language needs is translated from the source's same
         * form, or from `other` when the source language doesn't have it (English has no `few`).
         */
        fun build(set: TranslationSet, locale: Locale, keys: Collection<ResourceKey>): TranslationPlan {
            val segments = mutableListOf<Segment>()
            for (key in keys) {
                val source = set.source(key) ?: continue
                if (!source.translatable) continue
                when (val value = source.value) {
                    is TextValue -> if (value.text.isNotBlank()) segments += Segment(key, Part.Whole, value.text, source.comment)
                    is PluralsValue -> {
                        val fallback = value.items[PluralCategory.OTHER] ?: value.items.values.firstOrNull() ?: continue
                        for (category in PluralRules.categories(locale).sorted()) {
                            segments += Segment(key, Part.Plural(category), value.items[category] ?: fallback, source.comment)
                        }
                    }
                    is ArrayValue -> value.items.forEachIndexed { i, text -> segments += Segment(key, Part.Item(i), text, source.comment) }
                }
            }
            return TranslationPlan(locale, segments)
        }
    }
}
