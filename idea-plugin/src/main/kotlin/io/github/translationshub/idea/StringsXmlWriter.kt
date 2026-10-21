package io.github.translationshub.idea

import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import io.github.translationshub.core.PluralCategory
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceKind

/**
 * Edits `strings.xml` through the XML PSI, touching only the tag that changes so comments, ordering, blank lines
 * and the user's formatting survive. Call inside a write command (see [TranslationsWriteCommand]) so each edit is
 * one undo step.
 *
 * Text is in the model's form (see [io.github.translationshub.core.ResourceValue]): XML entities decoded, Compose
 * escapes and inline markup as written.
 */
object StringsXmlWriter {

    fun findTag(file: XmlFile, key: ResourceKey): XmlTag? =
        file.rootTag?.subTags?.firstOrNull { it.name == key.kind.xmlTag && it.getAttributeValue("name") == key.name }

    /** Sets a `<string>`'s text, adding the tag (after [after], or at the end) if this file doesn't have it yet. */
    fun setText(file: XmlFile, name: String, text: String, after: ResourceKey? = null): XmlTag {
        val key = ResourceKey(ResourceKind.STRING, name)
        val tag = findTag(file, key) ?: return addTag(file, key, after, body = escape(text))
        return setContent(tag, text)
    }

    /** Sets one `<item quantity=…>` of a `<plurals>`, keeping the items in CLDR order. */
    fun setPluralItem(file: XmlFile, name: String, category: PluralCategory, text: String, after: ResourceKey? = null): XmlTag {
        val key = ResourceKey(ResourceKind.PLURALS, name)
        val plurals = findTag(file, key) ?: addTag(file, key, after, body = null)
        val items = plurals.findSubTags("item")
        val existing = items.firstOrNull { it.getAttributeValue("quantity") == category.xmlName }
        if (existing != null) return setContent(existing, text)
        val item = plurals.createChildTag("item", plurals.namespace, escape(text), false)
        item.setAttribute("quantity", category.xmlName)
        val next = items.firstOrNull { other ->
            PluralCategory.fromXml(other.getAttributeValue("quantity") ?: "")?.let { it > category } == true
        }
        return if (next != null) plurals.addBefore(item, next) as XmlTag else plurals.addSubTag(item, false)
    }

    /** Removes one `<item quantity=…>` of a `<plurals>`; false if it wasn't there. */
    fun removePluralItem(file: XmlFile, name: String, category: PluralCategory): Boolean {
        val plurals = findTag(file, ResourceKey(ResourceKind.PLURALS, name)) ?: return false
        val item = plurals.findSubTags("item").firstOrNull { it.getAttributeValue("quantity") == category.xmlName } ?: return false
        deleteWithLine(item)
        return true
    }

    /** Replaces a `<string-array>`'s items. */
    fun setArrayItems(file: XmlFile, name: String, items: List<String>, after: ResourceKey? = null): XmlTag {
        val key = ResourceKey(ResourceKind.ARRAY, name)
        val array = findTag(file, key) ?: addTag(file, key, after, body = null)
        val existing = array.findSubTags("item")
        items.forEachIndexed { i, text ->
            val item = existing.getOrNull(i)
            if (item != null) {
                setContent(item, text)
            } else {
                array.addSubTag(array.createChildTag("item", array.namespace, escape(text), false), false)
            }
        }
        existing.drop(items.size).forEach(::deleteWithLine)
        return array
    }

    fun delete(file: XmlFile, key: ResourceKey): Boolean {
        val tag = findTag(file, key) ?: return false
        deleteWithLine(tag)
        return true
    }

    fun rename(file: XmlFile, key: ResourceKey, newName: String): Boolean {
        val tag = findTag(file, key) ?: return false
        tag.setAttribute("name", newName)
        return true
    }

    /**
     * Adds an empty `<string>`, `<plurals>` or `<string-array>` right after [after] when this file has it, so the
     * locale file keeps the source file's order; otherwise at the end.
     */
    private fun addTag(file: XmlFile, key: ResourceKey, after: ResourceKey?, body: String?): XmlTag {
        val root = requireNotNull(file.rootTag) { "${file.name} has no <resources> tag" }
        val tag = root.createChildTag(key.kind.xmlTag, root.namespace, body ?: "", false)
        tag.setAttribute("name", key.name)
        val anchor = after?.let { findTag(file, it) }
        return if (anchor != null) root.addAfter(tag, anchor) as XmlTag else root.addSubTag(tag, false)
    }

    /**
     * Replaces a tag's content. Plain text goes through [com.intellij.psi.xml.XmlTagValue.setEscapedText]. Text with
     * inline markup is written into the document directly: setEscapedText would keep only the text parts, and
     * replacing the tag through the PSI runs the formatter over it.
     */
    private fun setContent(tag: XmlTag, text: String): XmlTag {
        val xml = escape(text)
        if (tag.value.text == xml) return tag
        if (!xml.contains('<')) {
            tag.value.setEscapedText(xml)
            return tag
        }
        val file = tag.containingFile
        val documents = PsiDocumentManager.getInstance(tag.project)
        val document = requireNotNull(documents.getDocument(file)) { "${file.name} has no document" }
        documents.doPostponedOperationsAndUnblockDocument(document)
        val tagStart = tag.textRange.startOffset
        val content = tag.value.textRange
        if (tag.text.substring(0, content.startOffset - tagStart).endsWith("/>")) {
            // <string name="x"/>: write the end tag too.
            document.replaceString(tag.textRange.endOffset - 2, tag.textRange.endOffset, ">$xml</${tag.name}>")
        } else {
            document.replaceString(content.startOffset, content.endOffset, xml)
        }
        documents.commitDocument(document)
        return requireNotNull(PsiTreeUtil.findElementOfClassAtOffset(file, tagStart, XmlTag::class.java, true)) {
            "Lost the <${tag.name}> tag after writing it"
        }
    }

    /** Deletes a tag and the whitespace before it, so no blank line is left behind. */
    private fun deleteWithLine(tag: XmlTag) {
        (tag.prevSibling as? PsiWhiteSpace)?.takeIf { it.text.count { c -> c == '\n' } == 1 }?.delete()
        tag.delete()
    }

    /**
     * Model text → XML text: `&` and a `<` that doesn't start a tag are escaped; inline markup such as `<b>` or
     * `<xliff:g id="x">` is kept. Compose escapes (`\'`, `\n`) are already in the model text and pass through.
     */
    fun escape(text: String): String {
        val out = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '&' -> out.append("&amp;")
                c == '<' && !startsTag(text, i) -> out.append("&lt;")
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private val tagStart = Regex("""</?[A-Za-z][\w:.-]*(\s+[\w:.-]+\s*=\s*"[^"<]*")*\s*/?>""")

    private fun startsTag(text: String, at: Int) = tagStart.matchAt(text, at) != null
}
