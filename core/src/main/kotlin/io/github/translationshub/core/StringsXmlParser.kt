package io.github.translationshub.core

import java.io.Reader
import java.io.StringReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants.CDATA
import javax.xml.stream.XMLStreamConstants.CHARACTERS
import javax.xml.stream.XMLStreamConstants.COMMENT
import javax.xml.stream.XMLStreamConstants.END_ELEMENT
import javax.xml.stream.XMLStreamConstants.ENTITY_REFERENCE
import javax.xml.stream.XMLStreamConstants.SPACE
import javax.xml.stream.XMLStreamConstants.START_ELEMENT
import javax.xml.stream.XMLStreamReader

/**
 * Reads a Compose Multiplatform (or Android) `values` XML file. Used for indexing and checks outside the editor;
 * edits in the IDE go through the XML PSI so formatting and comments survive.
 */
object StringsXmlParser {

    private val factory: XMLInputFactory = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false)
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.IS_COALESCING, true)
    }

    fun parse(text: String): List<ResourceEntry> = parse(StringReader(text))

    fun parse(reader: Reader): List<ResourceEntry> {
        val xml = factory.createXMLStreamReader(reader)
        try {
            val entries = mutableListOf<ResourceEntry>()
            var depth = 0
            var pendingComment: String? = null
            while (xml.hasNext()) {
                when (xml.next()) {
                    START_ELEMENT -> {
                        depth++
                        val kind = ResourceKind.fromXml(xml.localName)
                        if (depth == 2 && kind != null) {
                            val name = xml.attr("name")
                            val translatable = xml.attr("translatable") != "false"
                            val value = when (kind) {
                                ResourceKind.STRING -> TextValue(readInner(xml))
                                ResourceKind.PLURALS -> PluralsValue(readItems(xml).mapNotNull { (quantity, text) ->
                                    PluralCategory.fromXml(quantity ?: "")?.let { it to text }
                                }.toMap())
                                ResourceKind.ARRAY -> ArrayValue(readItems(xml).map { it.second })
                            }
                            depth--
                            if (name != null) entries += ResourceEntry(name, value, translatable, pendingComment)
                            pendingComment = null
                        }
                    }
                    END_ELEMENT -> depth--
                    COMMENT -> if (depth == 1) pendingComment = xml.text.trim()
                    CHARACTERS -> if (depth == 1 && xml.text.count { it == '\n' } > 1) pendingComment = null
                }
            }
            return entries
        } finally {
            xml.close()
        }
    }

    private fun XMLStreamReader.attr(name: String): String? =
        (0 until attributeCount).firstOrNull { getAttributeLocalName(it) == name }?.let { getAttributeValue(it) }

    /** `<item>` children of a `<plurals>` or `<string-array>`, with their `quantity`. Leaves the reader on the end tag. */
    private fun readItems(xml: XMLStreamReader): List<Pair<String?, String>> {
        val items = mutableListOf<Pair<String?, String>>()
        while (xml.hasNext()) {
            when (xml.next()) {
                START_ELEMENT -> if (xml.localName == "item") items += xml.attr("quantity") to readInner(xml)
                    else readInner(xml)
                END_ELEMENT -> return items
            }
        }
        return items
    }

    /** The content of the current element as written, inline markup included. Leaves the reader on its end tag. */
    private fun readInner(xml: XMLStreamReader): String {
        val out = StringBuilder()
        var depth = 0
        while (xml.hasNext()) {
            when (xml.next()) {
                CHARACTERS, CDATA, SPACE, ENTITY_REFERENCE -> out.append(xml.text)
                START_ELEMENT -> {
                    depth++
                    out.append('<').append(xml.localName.let { if (xml.prefix.isNullOrEmpty()) it else "${xml.prefix}:$it" })
                    for (i in 0 until xml.attributeCount) {
                        val prefix = xml.getAttributePrefix(i)
                        val attrName = if (prefix.isNullOrEmpty()) xml.getAttributeLocalName(i) else "$prefix:${xml.getAttributeLocalName(i)}"
                        out.append(' ').append(attrName).append("=\"").append(xml.getAttributeValue(i)).append('"')
                    }
                    out.append('>')
                }
                END_ELEMENT -> {
                    if (depth == 0) return out.toString()
                    depth--
                    out.append("</").append(xml.localName.let { if (xml.prefix.isNullOrEmpty()) it else "${xml.prefix}:$it" }).append('>')
                }
            }
        }
        return out.toString()
    }
}
