package io.github.translationshub.idea

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceKind

/** Every `composeResources` folder in the project's content, cached until files are added, moved or deleted. */
object ResourceRoots {

    private val skipped = setOf("build", ".gradle", ".git", ".idea", ".kotlin", "node_modules")

    fun all(project: Project): List<VirtualFile> = CachedValuesManager.getManager(project).getCachedValue(project) {
        CachedValueProvider.Result.create(scan(project), VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
    }

    /** The folders whose module is closest to [near] first: `Res` is generated per module. */
    fun nearest(project: Project, near: VirtualFile): List<VirtualFile> =
        all(project).sortedByDescending { commonPrefix(it.path, near.path) }

    private fun scan(project: Project): List<VirtualFile> {
        val index = ProjectFileIndex.getInstance(project)
        val found = mutableListOf<VirtualFile>()
        for (contentRoot in ProjectRootManager.getInstance(project).contentRoots) {
            VfsUtilCore.visitChildrenRecursively(contentRoot, object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (!file.isDirectory) return false
                    if (file.name in skipped || index.isExcluded(file)) return false
                    if (file.name == ResourceFolders.ROOT_NAME) {
                        found += file
                        return false
                    }
                    return true
                }
            })
        }
        return found.distinct()
    }

    private fun commonPrefix(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }
}

/** Where Kotlin code uses a key: `Res.string.name`, `Res.plurals.name`, `Res.array.name`. */
object ResourceUsages {

    private val kinds = mapOf("string" to ResourceKind.STRING, "plurals" to ResourceKind.PLURALS, "array" to ResourceKind.ARRAY)
    val accessor = Regex("""\bRes\s*\.\s*(string|plurals|array)\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)""")

    /** The key whose accessor covers [offset] in [text], with the name's range. */
    fun keyAt(text: CharSequence, offset: Int): Pair<ResourceKey, IntRange>? {
        val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        val line = text.subSequence(lineStart, lineEnd)
        for (match in accessor.findAll(line)) {
            val name = match.groups[2]!!
            val range = (name.range.first + lineStart)..(name.range.last + lineStart)
            if (offset in range.first..range.last + 1) return ResourceKey(kinds.getValue(match.groupValues[1]), name.value) to range
        }
        return null
    }

    /** Kotlin files that use [key], with the offsets of each use. Uses the word index, so only files with the name are read. */
    fun find(project: Project, key: ResourceKey, scope: GlobalSearchScope = GlobalSearchScope.projectScope(project)): List<Pair<VirtualFile, Int>> {
        val result = mutableListOf<Pair<VirtualFile, Int>>()
        val kind = kinds.entries.first { it.value == key.kind }.key
        val psiManager = PsiManager.getInstance(project)
        PsiSearchHelper.getInstance(project).processCandidateFilesForText(scope, UsageSearchContext.IN_CODE, true, key.name) { file ->
            if (file.extension == "kt") {
                val text = psiManager.findFile(file)?.viewProvider?.contents ?: return@processCandidateFilesForText true
                accessor.findAll(text).filter { it.groupValues[1] == kind && it.groupValues[2] == key.name }
                    .forEach { result += file to it.groups[2]!!.range.first }
            }
            true
        }
        return result
    }
}
