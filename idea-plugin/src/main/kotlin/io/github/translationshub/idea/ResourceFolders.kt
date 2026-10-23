package io.github.translationshub.idea

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import io.github.translationshub.core.AndroidText
import io.github.translationshub.core.FolderQualifiers
import io.github.translationshub.core.ResourceFormat
import io.github.translationshub.core.StringsFile
import io.github.translationshub.core.StringsXmlParser
import io.github.translationshub.core.TranslationSet

/** Finds `composeResources` and Android `res` folders and reads them, including unsaved edits in open editors. */
object ResourceFolders {

    const val ROOT_NAME = "composeResources"

    /** The `composeResources` or `res` folder of an XML file in a `values…` folder, or null if [file] isn't one. */
    fun rootOf(file: VirtualFile): VirtualFile? {
        if (file.isDirectory || file.extension != "xml") return null
        val folder = file.parent ?: return null
        val root = folder.parent ?: return null
        return root.takeIf { ResourceFormat.ofRoot(it.name) != null && FolderQualifiers.parse(folder.name) != null }
    }

    fun format(root: VirtualFile): ResourceFormat = ResourceFormat.ofRoot(root.name) ?: ResourceFormat.COMPOSE

    /**
     * Whether [file] holds strings, plurals or arrays. An Android `values` folder also has colors, dimensions and
     * themes, which get no Translations tab; `composeResources` values files are all strings.
     */
    fun hasStrings(file: VirtualFile): Boolean {
        if (rootOf(file)?.let(::format) != ResourceFormat.ANDROID) return true
        val text = FileDocumentManager.getInstance().getCachedDocument(file)?.charsSequence
            ?: runCatching { String(file.contentsToByteArray(), file.charset) }.getOrNull() ?: return false
        return stringTag.containsMatchIn(text)
    }

    private val stringTag = Regex("""<(string|plurals|string-array)[\s>]""")

    fun xmlFiles(root: VirtualFile): List<VirtualFile> = root.children
        .filter { it.isDirectory && FolderQualifiers.parse(it.name) != null }
        .sortedBy { it.name }
        .flatMap { folder -> folder.children.filter { !it.isDirectory && it.extension == "xml" }.sortedBy { it.name } }

    /** Reads every values file of [root]; files open in an editor are read from their document, unsaved text included. */
    fun read(root: VirtualFile): TranslationSet {
        val documents = FileDocumentManager.getInstance()
        val format = format(root)
        val files = xmlFiles(root).mapNotNull { file ->
            val qualifiers = FolderQualifiers.parse(file.parent.name) ?: return@mapNotNull null
            val text = documents.getCachedDocument(file)?.text ?: String(file.contentsToByteArray(), file.charset)
            val entries = runCatching { StringsXmlParser.parse(text) }.getOrElse { emptyList() }
            val untranslatable = format == ResourceFormat.ANDROID && AndroidText.isUntranslatableFile(file.name, text)
            StringsFile(file.path, qualifiers, when {
                untranslatable -> entries.map { it.copy(translatable = false) }
                format == ResourceFormat.ANDROID -> entries.map(::markReferences)
                else -> entries
            })
        }
        return TranslationSet(root.path, files, format)
    }

    /** `@string/app_name`, or an array of references: nothing to translate. */
    private fun markReferences(entry: io.github.translationshub.core.ResourceEntry) =
        if (AndroidText.isReferenceValue(entry.value)) entry.copy(translatable = false) else entry
}
