package io.github.translationshub.idea

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import io.github.translationshub.core.CheckOptions
import io.github.translationshub.core.ResourceFormat
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceKind

/** The checks the table and the inspection run: the free set, or everything with Pro. */
object CheckSettings {

    fun options(project: Project, root: VirtualFile): CheckOptions = when {
        !ProLicense.isLicensed() -> CheckOptions.FREE
        // Only Res.string uses are found so far; Android's R.string ones aren't, so no key would count as used.
        ResourceFolders.format(root) == ResourceFormat.ANDROID -> CheckOptions()
        else -> CheckOptions(usedKeys = UsedKeys.all(project))
    }
}

/**
 * Every `Res.string.x`, `Res.plurals.x` and `Res.array.x` in the project's Kotlin files, cached until code changes.
 * One pass over the files for the whole project, rather than a search per key.
 */
object UsedKeys {

    private val kinds = mapOf("string" to ResourceKind.STRING, "plurals" to ResourceKind.PLURALS, "array" to ResourceKind.ARRAY)

    fun all(project: Project): Set<ResourceKey> = CachedValuesManager.getManager(project).getCachedValue(project) {
        CachedValueProvider.Result.create(collect(project), PsiModificationTracker.getInstance(project))
    }

    private fun collect(project: Project): Set<ResourceKey> {
        val documents = FileDocumentManager.getInstance()
        val keys = HashSet<ResourceKey>()
        for (file in FilenameIndex.getAllFilesByExt(project, "kt", GlobalSearchScope.projectScope(project))) {
            val text = documents.getCachedDocument(file)?.charsSequence ?: runCatching { String(file.contentsToByteArray(), file.charset) }.getOrNull() ?: continue
            for (match in ResourceUsages.accessor.findAll(text)) keys += ResourceKey(kinds.getValue(match.groupValues[1]), match.groupValues[2])
        }
        return keys
    }
}
