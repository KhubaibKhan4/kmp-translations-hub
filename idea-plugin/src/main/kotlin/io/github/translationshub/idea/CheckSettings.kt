package io.github.translationshub.idea

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.util.CachedValue
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import io.github.translationshub.core.CheckOptions
import io.github.translationshub.core.ResourceFormat
import io.github.translationshub.core.ResourceKey

/** The checks the table and the inspection run: the free set, or everything with Pro. */
object CheckSettings {

    fun options(project: Project, root: VirtualFile): CheckOptions {
        if (!ProLicense.isLicensed()) return CheckOptions.FREE
        val format = ResourceFolders.format(root)
        // A String Catalog's uses in Swift aren't searched: no key would count as used.
        return CheckOptions(usedKeys = if (format == ResourceFormat.IOS) null else UsedKeys.all(project, format))
    }
}

/**
 * Every key the project's code uses, by format: `Res.string.x` in Kotlin for Compose; `R.string.x` in Kotlin and Java
 * and `@string/x` in XML for Android. Cached until code changes; one pass over the files for the whole project, rather
 * than a search per key.
 */
object UsedKeys {

    private val cacheKeys = ResourceFormat.entries.associateWith { Key.create<CachedValue<Set<ResourceKey>>>("kmp.translations.usedKeys.${it.name}") }

    fun all(project: Project, format: ResourceFormat = ResourceFormat.COMPOSE): Set<ResourceKey> =
        CachedValuesManager.getManager(project).getCachedValue(project, cacheKeys.getValue(format), {
            CachedValueProvider.Result.create(collect(project, format), PsiModificationTracker.getInstance(project))
        }, false)

    private fun collect(project: Project, format: ResourceFormat): Set<ResourceKey> {
        val documents = FileDocumentManager.getInstance()
        val keys = HashSet<ResourceKey>()
        for (extension in ResourceUsages.extensions(format)) {
            for (file in FilenameIndex.getAllFilesByExt(project, extension, GlobalSearchScope.projectScope(project))) {
                val text = documents.getCachedDocument(file)?.charsSequence ?: runCatching { String(file.contentsToByteArray(), file.charset) }.getOrNull() ?: continue
                ResourceUsages.keysIn(text, extension, format).forEach(keys::add)
            }
        }
        return keys
    }
}
