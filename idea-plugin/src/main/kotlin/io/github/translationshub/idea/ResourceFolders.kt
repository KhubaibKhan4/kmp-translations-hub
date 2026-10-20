package io.github.translationshub.idea

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import io.github.translationshub.core.FolderQualifiers
import io.github.translationshub.core.StringsFile
import io.github.translationshub.core.StringsXmlParser
import io.github.translationshub.core.TranslationSet

/** Finds `composeResources` folders and reads them, including unsaved edits in open editors. */
object ResourceFolders {

    const val ROOT_NAME = "composeResources"

    /** The `composeResources` folder of an XML file in a `values…` folder, or null if [file] isn't one. */
    fun rootOf(file: VirtualFile): VirtualFile? {
        if (file.isDirectory || file.extension != "xml") return null
        val folder = file.parent ?: return null
        val root = folder.parent ?: return null
        return root.takeIf { it.name == ROOT_NAME && FolderQualifiers.parse(folder.name) != null }
    }

    fun xmlFiles(root: VirtualFile): List<VirtualFile> = root.children
        .filter { it.isDirectory && FolderQualifiers.parse(it.name) != null }
        .sortedBy { it.name }
        .flatMap { folder -> folder.children.filter { !it.isDirectory && it.extension == "xml" }.sortedBy { it.name } }

    /** Reads every values file of [root]; files open in an editor are read from their document, unsaved text included. */
    fun read(root: VirtualFile): TranslationSet {
        val documents = FileDocumentManager.getInstance()
        val files = xmlFiles(root).mapNotNull { file ->
            val qualifiers = FolderQualifiers.parse(file.parent.name) ?: return@mapNotNull null
            val text = documents.getCachedDocument(file)?.text ?: String(file.contentsToByteArray(), file.charset)
            val entries = runCatching { StringsXmlParser.parse(text) }.getOrElse { emptyList() }
            StringsFile(file.path, qualifiers, entries)
        }
        return TranslationSet(root.path, files)
    }
}
