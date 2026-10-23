package io.github.translationshub.idea

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import io.github.translationshub.core.TranslationLock

/**
 * `translations.lock.json` beside a `composeResources` folder. It is only created when strings are machine
 * translated: projects that never use translation get no extra file, and their translations count as written by
 * a person.
 */
object LockFiles {

    fun find(root: VirtualFile): VirtualFile? = root.parent?.findChild(name(root))

    /**
     * `translations.lock.json` beside a `values…` folders' root; `Localizable.translations.lock.json` beside a String
     * Catalog, since an app often has two (Localizable, InfoPlist) in one folder.
     */
    fun name(root: VirtualFile): String =
        if (ResourceFolders.isCatalog(root)) "${root.nameWithoutExtension}.${TranslationLock.FILE_NAME}" else TranslationLock.FILE_NAME

    /** The lock as saved, unsaved edits included; empty when there is no file or it can't be read. */
    fun read(root: VirtualFile): TranslationLock {
        val file = find(root) ?: return TranslationLock()
        val text = FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: String(file.contentsToByteArray(), Charsets.UTF_8)
        return runCatching { TranslationLock.fromJson(text) }.getOrElse { TranslationLock() }
    }

    /**
     * Writes [lock] through the file's document, so it is part of the running command's Undo. Call inside a write
     * command. With [create] false, nothing happens when the project has no lock file yet.
     */
    fun write(root: VirtualFile, lock: TranslationLock, create: Boolean, requestor: Any) {
        val file = find(root) ?: if (create) root.parent.createChildData(requestor, name(root)) else return
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val json = lock.toJson()
        if (document.text != json) document.setText(json)
    }
}
