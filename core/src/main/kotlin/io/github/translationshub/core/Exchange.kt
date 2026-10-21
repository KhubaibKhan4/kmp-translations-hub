package io.github.translationshub.core

import java.io.StringReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * Files for translators and translation tools. Every format works on segments: a string is one (`hello`), each
 * plural form is one (`files#few`), each array item is one (`days[2]`), so tools that only know flat strings can
 * still handle plurals and arrays.
 */
object Exchange {

    /** What an import found for one locale: the values to write, and the ids it skipped (not in the default values). */
    data class Imported(val values: Map<ResourceKey, ResourceValue>, val skipped: List<String>)

    data class Segment(val id: String, val key: ResourceKey, val text: String)

    fun segments(value: ResourceValue, name: String): List<Segment> = when (value) {
        is TextValue -> listOf(Segment(name, ResourceKey(ResourceKind.STRING, name), value.text))
        is PluralsValue -> value.items.toSortedMap().map { (category, text) -> Segment("$name#${category.xmlName}", ResourceKey(ResourceKind.PLURALS, name), text) }
        is ArrayValue -> value.items.mapIndexed { i, text -> Segment("$name[$i]", ResourceKey(ResourceKind.ARRAY, name), text) }
    }

    // ---- CSV: key, default, then one column per locale -------------------------------------------------------

    fun toCsv(set: TranslationSet): String = buildString {
        appendCsvRow(listOf("key", "default") + set.locales.map { it.tag })
        for (key in set.keys) {
            val source = set.source(key) ?: continue
            if (!source.translatable) continue
            val translations = set.locales.map { locale -> set.translation(key, locale)?.let { segments(it.value, key.name) }.orEmpty().associateBy { it.id } }
            for (unit in segments(source.value, key.name)) {
                appendCsvRow(listOf(unit.id, unit.text) + translations.map { it[unit.id]?.text.orEmpty() })
            }
        }
    }

    /** Reads a CSV made by [toCsv] (or edited in a spreadsheet); empty cells are left out. */
    fun fromCsv(text: String, set: TranslationSet): Map<Locale, Imported> {
        val rows = parseCsv(text)
        val header = rows.firstOrNull() ?: return emptyMap()
        val columns = header.mapIndexedNotNull { i, name -> if (i < 2) null else Locale.fromTag(name.trim())?.let { i to it } }
        return columns.associate { (column, locale) ->
            locale to assemble(rows.drop(1).mapNotNull { row -> row.getOrNull(column)?.takeIf { it.isNotEmpty() }?.let { row[0] to it } }, set, locale)
        }
    }

    // ---- XLIFF 1.2: one file per locale ----------------------------------------------------------------------

    fun toXliff(set: TranslationSet, locale: Locale, sourceLanguage: String = "en"): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<xliff version=\"1.2\" xmlns=\"urn:oasis:names:tc:xliff:document:1.2\">\n")
        append("  <file original=\"composeResources\" source-language=\"").append(sourceLanguage)
        append("\" target-language=\"").append(locale.tag).append("\" datatype=\"plaintext\">\n    <body>\n")
        for (key in set.keys) {
            val source = set.source(key) ?: continue
            if (!source.translatable) continue
            val targets = set.translation(key, locale)?.let { segments(it.value, key.name) }.orEmpty().associateBy { it.id }
            for (unit in segments(source.value, key.name)) {
                append("      <trans-unit id=\"").append(xml(unit.id)).append("\">\n")
                append("        <source>").append(xml(unit.text)).append("</source>\n")
                targets[unit.id]?.let { append("        <target>").append(xml(it.text)).append("</target>\n") }
                source.comment?.let { append("        <note>").append(xml(it)).append("</note>\n") }
                append("      </trans-unit>\n")
            }
        }
        append("    </body>\n  </file>\n</xliff>\n")
    }

    /** The target locale and the translations of an XLIFF 1.2 file; [fallback] when the file doesn't name one. */
    fun fromXliff(text: String, set: TranslationSet, fallback: Locale? = null): Pair<Locale?, Imported> {
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_COALESCING, true)
        }
        val reader = factory.createXMLStreamReader(StringReader(text))
        var locale: Locale? = null
        val pairs = mutableListOf<Pair<String, String>>()
        var id: String? = null
        var inTarget = false
        val target = StringBuilder()
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "file" -> locale = locale ?: reader.getAttributeValue(null, "target-language")?.let(Locale::fromTag)
                    "trans-unit" -> id = reader.getAttributeValue(null, "id")
                    "target" -> { inTarget = true; target.clear() }
                }
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (inTarget) target.append(reader.text)
                XMLStreamConstants.END_ELEMENT -> when (reader.localName) {
                    "target" -> {
                        inTarget = false
                        if (id != null && target.isNotEmpty()) pairs += id!! to target.toString()
                    }
                    "trans-unit" -> id = null
                }
            }
        }
        reader.close()
        val targetLocale = locale ?: fallback
        return targetLocale to assemble(pairs, set, targetLocale)
    }

    // ---- Android strings.xml: the same format, written fresh ---------------------------------------------

    /** A `strings.xml` with [locale]'s translations, in the default file's order, for tools that read Android resources. */
    fun toStringsXml(set: TranslationSet, locale: Locale): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
        for (key in set.keys) {
            val entry = set.translation(key, locale) ?: continue
            when (val value = entry.value) {
                is TextValue -> append("    <string name=\"").append(key.name).append("\">").append(xmlText(value.text)).append("</string>\n")
                is PluralsValue -> {
                    append("    <plurals name=\"").append(key.name).append("\">\n")
                    for ((category, text) in value.items.toSortedMap()) {
                        append("        <item quantity=\"").append(category.xmlName).append("\">").append(xmlText(text)).append("</item>\n")
                    }
                    append("    </plurals>\n")
                }
                is ArrayValue -> {
                    append("    <string-array name=\"").append(key.name).append("\">\n")
                    for (text in value.items) append("        <item>").append(xmlText(text)).append("</item>\n")
                    append("    </string-array>\n")
                }
            }
        }
        append("</resources>\n")
    }

    /** Translations from a `strings.xml` (Android `res/values-xx` or another project's `composeResources`). */
    fun fromStringsXml(text: String, set: TranslationSet): Imported {
        val entries = StringsXmlParser.parse(text)
        val values = entries.filter { set.source(it.key)?.translatable == true }.associate { it.key to it.value }
        return Imported(values, entries.filter { set.source(it.key) == null }.map { it.name })
    }

    /** Segments back into values, merged onto [locale]'s current translation so a file with only some plural forms or items keeps the rest. */
    private fun assemble(pairs: List<Pair<String, String>>, set: TranslationSet, locale: Locale?): Imported {
        val skipped = mutableListOf<String>()
        val strings = LinkedHashMap<ResourceKey, ResourceValue>()
        val plurals = LinkedHashMap<String, MutableMap<PluralCategory, String>>()
        val arrays = LinkedHashMap<String, MutableMap<Int, String>>()
        for ((id, text) in pairs) {
            val plural = Regex("""^(.+)#(zero|one|two|few|many|other)$""").find(id)
            val item = Regex("""^(.+)\[(\d+)]$""").find(id)
            when {
                plural != null && set.source(ResourceKey(ResourceKind.PLURALS, plural.groupValues[1])) != null ->
                    plurals.getOrPut(plural.groupValues[1]) { linkedMapOf() }[PluralCategory.fromXml(plural.groupValues[2])!!] = text
                item != null && set.source(ResourceKey(ResourceKind.ARRAY, item.groupValues[1])) != null ->
                    arrays.getOrPut(item.groupValues[1]) { linkedMapOf() }[item.groupValues[2].toInt()] = text
                set.source(ResourceKey(ResourceKind.STRING, id)) != null -> strings[ResourceKey(ResourceKind.STRING, id)] = TextValue(text)
                else -> skipped += id
            }
        }
        fun current(key: ResourceKey) = locale?.let { set.translation(key, it)?.value }
        for ((name, items) in plurals) {
            val key = ResourceKey(ResourceKind.PLURALS, name)
            val existing = (current(key) as? PluralsValue)?.items.orEmpty()
            strings[key] = PluralsValue((existing + items).toSortedMap())
        }
        for ((name, items) in arrays) {
            val key = ResourceKey(ResourceKind.ARRAY, name)
            val existing = (current(key) as? ArrayValue)?.items.orEmpty()
            val sourceSize = (set.source(key)?.value as ArrayValue).items.size
            val size = maxOf(sourceSize, existing.size, (items.keys.maxOrNull() ?: -1) + 1)
            strings[key] = ArrayValue((0 until size).map { items[it] ?: existing.getOrNull(it).orEmpty() })
        }
        return Imported(strings.filterKeys { set.source(it)?.translatable == true }, skipped)
    }

    private fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** For strings.xml: like the plugin's writer, inline markup such as `<b>` is kept. */
    private fun xmlText(text: String): String {
        val tag = Regex("""</?[A-Za-z][\w:.-]*(\s+[\w:.-]+\s*=\s*"[^"<]*")*\s*/?>""")
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '&' -> out.append("&amp;")
                c == '<' && tag.matchAt(text, i) == null -> out.append("&lt;")
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun StringBuilder.appendCsvRow(cells: List<String>) {
        cells.joinTo(this, ",") { cell ->
            if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
        }
        append("\r\n")
    }

    /** RFC 4180: quoted cells may hold commas, quotes ("") and line breaks. */
    fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = if (text.startsWith("﻿")) 1 else 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> cell.append(c)
                c == '"' -> quoted = true
                c == ',' -> { row += cell.toString(); cell.clear() }
                c == '\n' || c == '\r' -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.clear()
                    rows += row; row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows.filter { r -> r.any { it.isNotEmpty() } }
    }
}
