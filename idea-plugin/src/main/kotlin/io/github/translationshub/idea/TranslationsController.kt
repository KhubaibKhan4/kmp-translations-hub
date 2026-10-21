package io.github.translationshub.idea

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import io.github.translationshub.core.ArrayValue
import io.github.translationshub.core.FolderQualifiers
import io.github.translationshub.core.Locale
import io.github.translationshub.core.Origin
import io.github.translationshub.core.PluralCategory
import io.github.translationshub.core.PluralsValue
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceKind
import io.github.translationshub.core.ResourceValue
import io.github.translationshub.core.TextValue
import io.github.translationshub.core.TranslationLock
import io.github.translationshub.core.TranslationSet

/**
 * Every change to one `composeResources` folder: cells, keys, locales, and machine translations. Each call is one
 * command, so one Undo, and updates `translations.lock.json` in the same command when the project has one.
 */
class TranslationsController(private val project: Project, val root: VirtualFile) {

    /** Fresh from disk and open documents; cheap enough (see the 2,000 × 15 test) to read before every change. */
    fun read(): TranslationSet = ResourceFolders.read(root)

    fun lock(): TranslationLock = LockFiles.read(root).also { it.adoptUntracked(read()) }

    /** Sets one cell: [locale] null is the default (source) column. Typing in a cell makes it a person's translation. */
    fun setValue(key: ResourceKey, locale: Locale?, value: ResourceValue) {
        val set = read()
        val target = targetFile(set, key, locale) ?: error("No values${locale?.let { "-${it.qualifier}" } ?: ""} file for ${key.name}")
        command("Edit ${key.name} (${locale?.tag ?: "default"})", listOf(target)) {
            write(set, target, key, locale, value)
            val source = set.source(key)
            if (locale != null && source != null) updateLock(create = false) { it.put(key, locale, source.value, value, Origin.Human) }
        }
    }

    /**
     * Writes machine translations for one locale, as one command, and records them in the lock file (creating it):
     * from then on only new or changed source strings are sent again.
     */
    fun applyTranslations(locale: Locale, values: Map<ResourceKey, ResourceValue>, provider: String) {
        if (values.isEmpty()) return
        val set = read()
        val targets = values.keys.mapNotNull { key -> targetFile(set, key, locale)?.let { key to it } }.toMap()
        command("Translate ${values.size} strings to ${locale.tag}", targets.values.distinct()) {
            for ((key, target) in targets) write(set, target, key, locale, values.getValue(key))
            updateLock(create = true, before = set) { lock ->
                for (key in targets.keys) set.source(key)?.let { lock.put(key, locale, it.value, values.getValue(key), Origin.Machine(provider)) }
            }
        }
    }

    /** Adds a key to the default values file, at the end. */
    fun addKey(name: String, value: ResourceValue) {
        val set = read()
        val key = ResourceKey(value.kind, name)
        require(set.source(key) == null) { "${key.name} already exists" }
        val target = sourceFiles(set).firstOrNull() ?: error("No values folder in ${root.path}")
        command("Add ${key.name}", listOf(target)) { write(set, target, key, null, value) }
    }

    /** Renames a key in the default file and every locale file, and in the lock file. */
    fun renameKey(key: ResourceKey, newName: String) {
        val set = read()
        val newKey = key.copy(name = newName)
        require(set.source(newKey) == null) { "$newName already exists" }
        val files = filesWith(set, key)
        command("Rename ${key.name} to $newName", files) {
            files.forEach { StringsXmlWriter.rename(psi(it), key, newName) }
            updateLock(create = false) { it.rename(key, newKey) }
        }
    }

    /** Deletes a key everywhere: default file, every locale file, lock file. */
    fun deleteKey(key: ResourceKey) {
        val set = read()
        val files = filesWith(set, key)
        command("Delete ${key.name}", files) {
            files.forEach { StringsXmlWriter.delete(psi(it), key) }
            updateLock(create = false) { it.remove(key) }
        }
    }

    /** Creates `values-<locale>/` with an empty file named like the default one (usually `strings.xml`). */
    fun addLocale(locale: Locale): VirtualFile {
        val set = read()
        require(locale !in set.locales) { "${locale.tag} already exists" }
        val fileName = set.sourceFiles.firstOrNull()?.fileName ?: "strings.xml"
        lateinit var created: VirtualFile
        command("Add locale ${locale.tag}", emptyList()) {
            val folderName = FolderQualifiers(locale).folderName
            val folder = root.findChild(folderName) ?: root.createChildDirectory(this, folderName)
            created = folder.findChild(fileName) ?: folder.createChildData(this, fileName).also {
                val document = requireNotNull(FileDocumentManager.getInstance().getDocument(it))
                document.setText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n")
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
        }
        return created
    }

    private fun write(set: TranslationSet, target: VirtualFile, key: ResourceKey, locale: Locale?, value: ResourceValue) {
        require(value.kind == key.kind) { "A ${key.kind.xmlTag} can't hold a ${value.kind.xmlTag}" }
        val file = psi(target)
        val after = previousKeyIn(set, key, locale, target)
        when (value) {
            is TextValue -> StringsXmlWriter.setText(file, key.name, value.text, after)
            is PluralsValue -> {
                // Write the new forms first, so a missing <plurals> tag is created at the right place.
                for ((category, text) in value.items.toSortedMap()) StringsXmlWriter.setPluralItem(file, key.name, category, text, after)
                for (category in PluralCategory.entries - value.items.keys) StringsXmlWriter.removePluralItem(file, key.name, category)
            }
            is ArrayValue -> StringsXmlWriter.setArrayItems(file, key.name, value.items, after)
        }
    }

    /** The nearest key before [key] in the source order that [target] already has, so new entries keep that order. */
    private fun previousKeyIn(set: TranslationSet, key: ResourceKey, locale: Locale?, target: VirtualFile): ResourceKey? {
        val index = set.keys.indexOf(key).takeIf { it >= 0 } ?: return null
        return set.keys.subList(0, index).asReversed().firstOrNull { previous ->
            (if (locale == null) set.sourceFile(previous) else set.translationFile(previous, locale))?.path == target.path
        }
    }

    /** The file a cell is written to: the one that has the key, else the locale's file named like the source file. */
    private fun targetFile(set: TranslationSet, key: ResourceKey, locale: Locale?): VirtualFile? {
        val path = if (locale == null) {
            set.sourceFile(key)?.path ?: set.sourceFiles.firstOrNull()?.path
        } else {
            set.translationFile(key, locale)?.path ?: run {
                val sourceName = set.sourceFile(key)?.fileName
                val files = set.filesFor(locale)
                (files.firstOrNull { it.fileName == sourceName } ?: files.firstOrNull())?.path
            }
        }
        return path?.let(root.fileSystem::findFileByPath)
    }

    private fun sourceFiles(set: TranslationSet) = set.sourceFiles
        .sortedByDescending { it.fileName == "strings.xml" }
        .mapNotNull { root.fileSystem.findFileByPath(it.path) }

    private fun filesWith(set: TranslationSet, key: ResourceKey): List<VirtualFile> =
        (listOfNotNull(set.sourceFile(key)) + set.locales.mapNotNull { set.translationFile(key, it) })
            .mapNotNull { root.fileSystem.findFileByPath(it.path) }

    private fun psi(file: VirtualFile): XmlFile =
        requireNotNull(PsiManager.getInstance(project).findFile(file) as? XmlFile) { "${file.path} isn't an XML file" }

    /**
     * Applies [change] to the lock file. When [create] makes a new one, the translations already there are recorded
     * first, as they were [before] this command: a person's translation then shows as needing review once its
     * source changes, instead of being adopted later as current.
     */
    private fun updateLock(create: Boolean, before: TranslationSet? = null, change: (TranslationLock) -> Unit) {
        val exists = LockFiles.find(root) != null
        if (!create && !exists) return
        val lock = LockFiles.read(root)
        if (!exists && before != null) lock.adoptUntracked(before)
        change(lock)
        // The PSI edits of this command reach the documents, which read() uses, only once flushed.
        val documents = PsiDocumentManager.getInstance(project)
        ResourceFolders.xmlFiles(root).mapNotNull { FileDocumentManager.getInstance().getCachedDocument(it) }
            .forEach(documents::doPostponedOperationsAndUnblockDocument)
        lock.prune(read())
        LockFiles.write(root, lock, create, this)
    }

    private fun command(name: String, files: List<VirtualFile>, edit: () -> Unit) {
        TranslationsWriteCommand.run(project, name, files.distinct().map(::psi), edit)
    }

    companion object {
        /** Keys can't be empty or contain characters Compose's accessor generator rejects. */
        fun isValidKeyName(name: String) = Regex("[A-Za-z_][A-Za-z0-9_.]*").matches(name)

        fun emptyValue(kind: ResourceKind): ResourceValue = when (kind) {
            ResourceKind.STRING -> TextValue("")
            ResourceKind.PLURALS -> PluralsValue(mapOf(PluralCategory.OTHER to ""))
            ResourceKind.ARRAY -> ArrayValue(listOf(""))
        }
    }
}
