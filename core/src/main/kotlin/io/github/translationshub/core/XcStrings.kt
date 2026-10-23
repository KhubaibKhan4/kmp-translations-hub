package io.github.translationshub.core

/**
 * An Xcode String Catalog (`Localizable.xcstrings`): one JSON file with every language. Read into the same model as a
 * `values…` folder, one column per language, and written back by changing only the values that changed, in Xcode's
 * own layout, so a catalog the plugin didn't touch comes back byte for byte and a change shows as a small diff.
 *
 * A key is often the English text itself. A string with device variations or substitutions is kept as it is and not
 * offered for translation; plurals (`variations.plural`) are. A `new` localization (what Xcode adds for a language it
 * hasn't translated yet, usually with the English text) counts as missing.
 */
class XcStrings private constructor(private val root: MutableMap<String, Any?>, private val trailingNewline: Boolean) {

    val sourceLanguage: String get() = root["sourceLanguage"] as? String ?: "en"

    @Suppress("UNCHECKED_CAST")
    private val strings: MutableMap<String, Any?>
        get() = root.getOrPut("strings") { LinkedHashMap<String, Any?>() } as MutableMap<String, Any?>

    /** Every language the catalog has a localization for, the source language left out. */
    val locales: List<Locale>
        get() = strings.values.flatMap { (it as? Map<*, *>)?.get("localizations").let { l -> (l as? Map<*, *>)?.keys.orEmpty() } }
            .filterIsInstance<String>().distinct().filter { it != sourceLanguage }.mapNotNull(Locale::fromTag).sorted()

    /** The catalog as a [TranslationSet]: a file per language, all with [path], so the checks and the table work on it. */
    fun toSet(path: String): TranslationSet {
        val sourceLocale = Locale.fromTag(sourceLanguage)
        val source = StringsFile(path, FolderQualifiers(null), strings.mapNotNull { (key, value) -> entry(key, value as? Map<*, *>, null) })
        val translations = locales.filter { it != sourceLocale }.map { locale ->
            StringsFile(path, FolderQualifiers(locale), strings.mapNotNull { (key, value) -> entry(key, value as? Map<*, *>, locale) })
        }
        return TranslationSet(path, listOf(source) + translations, ResourceFormat.IOS)
    }

    private fun entry(key: String, string: Map<*, *>?, locale: Locale?): ResourceEntry? {
        val localizations = string?.get("localizations") as? Map<*, *>
        val localization = localizations?.get(locale?.tag ?: sourceLanguage) as? Map<*, *>
        val value = if (locale == null) value(localization) ?: TextValue(key) else value(localization?.takeUnless { isNew(it) }) ?: return null
        // Device variations or substitutions in any language: the model can't hold the string, so it's left alone.
        val supported = localizations?.values.orEmpty().all { it !is Map<*, *> || supported(it) }
        val translatable = string?.get("shouldTranslate") != false && supported && !isStale(string)
        return ResourceEntry(key, value, translatable = translatable, comment = string?.get("comment") as? String)
    }

    /** A plain string or a plural; null for what the model can't hold (device variations, substitutions). */
    private fun value(localization: Map<*, *>?): ResourceValue? {
        localization ?: return null
        unit(localization)?.let { return TextValue(it) }
        val plural = ((localization["variations"] as? Map<*, *>)?.get("plural") as? Map<*, *>) ?: return null
        val items = plural.entries.mapNotNull { (category, form) ->
            val name = PluralCategory.fromXml(category as String) ?: return@mapNotNull null
            unit(form as? Map<*, *>)?.let { name to it }
        }.toMap()
        return items.takeIf { it.isNotEmpty() }?.let(::PluralsValue)
    }

    private fun unit(holder: Map<*, *>?) = ((holder?.get("stringUnit") as? Map<*, *>)?.get("value")) as? String

    private fun supported(localization: Map<*, *>): Boolean {
        if ("substitutions" in localization) return false
        val variations = localization["variations"] as? Map<*, *> ?: return true
        return variations.keys == setOf("plural")
    }

    private fun isNew(localization: Map<*, *>): Boolean {
        val states = buildList {
            ((localization["stringUnit"] as? Map<*, *>)?.get("state") as? String)?.let(::add)
            ((localization["variations"] as? Map<*, *>)?.get("plural") as? Map<*, *>)?.values?.forEach { form ->
                (((form as? Map<*, *>)?.get("stringUnit") as? Map<*, *>)?.get("state") as? String)?.let(::add)
            }
        }
        return states.isNotEmpty() && states.all { it == "new" }
    }

    /** Xcode keeps strings the code no longer uses, as `stale`: nothing to translate. */
    private fun isStale(string: Map<*, *>?) = string?.get("extractionState") == "stale"

    // ---- Changes -------------------------------------------------------------------------------------------------

    /**
     * Sets [key]'s value for [locale] (null: the source language) as `translated`, keeping the string's other fields.
     * A plural writes its forms and drops the ones it doesn't have.
     */
    fun set(key: String, locale: Locale?, value: ResourceValue) {
        val string = map(strings, key)
        val localization = map(map(string, "localizations"), locale?.tag ?: sourceLanguage)
        when (value) {
            is TextValue -> {
                localization.remove("variations")
                localization["stringUnit"] = unitOf(value.text, localization["stringUnit"])
            }
            is PluralsValue -> {
                localization.remove("stringUnit")
                val plural = map(map(localization, "variations"), "plural")
                val old = LinkedHashMap(plural)
                plural.clear()
                // Xcode lists the forms alphabetically: few, many, one, other, two, zero.
                for ((category, text) in value.items.entries.sortedBy { it.key.xmlName }) {
                    plural[category.xmlName] = linkedMapOf<String, Any?>("stringUnit" to unitOf(text, (old[category.xmlName] as? Map<*, *>)?.get("stringUnit")))
                }
            }
            is ArrayValue -> error("A String Catalog has no arrays")
        }
    }

    /** Adds [locale] to every string that's translated, as `new` with the source text: what Xcode does for a new language. */
    fun addLocale(locale: Locale) {
        for ((key, value) in strings) {
            val string = value as? MutableMap<String, Any?> ?: continue
            if (string["shouldTranslate"] == false) continue
            val localizations = map(string, "localizations")
            if (locale.tag in localizations) continue
            val source = value(localizations[sourceLanguage] as? Map<*, *>) ?: TextValue(key)
            if (source !is TextValue) continue
            localizations[locale.tag] = linkedMapOf<String, Any?>("stringUnit" to linkedMapOf<String, Any?>("state" to "new", "value" to source.text))
        }
    }

    fun rename(key: String, newKey: String) {
        val entries = strings.entries.toList()
        strings.clear()
        for ((k, v) in entries) strings[if (k == key) newKey else k] = v
    }

    fun delete(key: String) {
        strings.remove(key)
    }

    /** Adds [key] with [value] in the source language, after the last string (Xcode re-sorts when it next saves). */
    fun add(key: String, value: ResourceValue) {
        strings.putIfAbsent(key, linkedMapOf<String, Any?>("extractionState" to "manual"))
        set(key, null, value)
    }

    private fun unitOf(text: String, old: Any?): MutableMap<String, Any?> =
        LinkedHashMap((old as? Map<*, *>)?.entries?.associate { it.key as String to it.value }.orEmpty()).apply {
            this["state"] = "translated"
            this["value"] = text
        }

    @Suppress("UNCHECKED_CAST")
    private fun map(parent: MutableMap<String, Any?>, key: String): MutableMap<String, Any?> =
        (parent[key] as? MutableMap<String, Any?>) ?: LinkedHashMap<String, Any?>().also { parent[key] = it }

    // ---- Writing -------------------------------------------------------------------------------------------------

    /** The catalog in Xcode's layout: 2-space indent, `"key" : value`, `{` blank line `}` for an empty object. */
    fun toJson(): String = buildString {
        write(root, 0)
        if (trailingNewline) append('\n')
    }

    private fun StringBuilder.write(value: Any?, depth: Int) {
        when (value) {
            null -> append("null")
            is String -> quote(value)
            is Boolean -> append(value)
            is Number -> append(value.toDouble().let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() })
            is Map<*, *> -> {
                append('{').append('\n')
                if (value.isEmpty()) append('\n')
                value.entries.forEachIndexed { i, (k, v) ->
                    indent(depth + 1)
                    quote(k as String)
                    append(" : ")
                    write(v, depth + 1)
                    if (i < value.size - 1) append(',')
                    append('\n')
                }
                indent(depth)
                append('}')
            }
            is List<*> -> {
                if (value.isEmpty()) return append("[]").let { }
                append('[').append('\n')
                value.forEachIndexed { i, v ->
                    indent(depth + 1)
                    write(v, depth + 1)
                    if (i < value.size - 1) append(',')
                    append('\n')
                }
                indent(depth)
                append(']')
            }
            else -> error("Can't write ${value::class.simpleName}")
        }
    }

    private fun StringBuilder.indent(depth: Int) = repeat(depth) { append("  ") }

    /** JSON string escapes as Xcode writes them: non-ASCII text as it is. */
    private fun StringBuilder.quote(s: String) {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    companion object {
        const val EXTENSION = "xcstrings"

        @Suppress("UNCHECKED_CAST")
        fun parse(text: String): XcStrings {
            val root = (if (text.isBlank()) null else Json.parse(text)) as? MutableMap<String, Any?>
                ?: linkedMapOf("sourceLanguage" to "en", "strings" to LinkedHashMap<String, Any?>(), "version" to "1.0")
            return XcStrings(deepMutable(root) as MutableMap<String, Any?>, text.endsWith("\n"))
        }

        private fun deepMutable(value: Any?): Any? = when (value) {
            is Map<*, *> -> value.entries.associateTo(LinkedHashMap<String, Any?>()) { it.key as String to deepMutable(it.value) }
            is List<*> -> value.mapTo(mutableListOf(), ::deepMutable)
            else -> value
        }
    }
}
