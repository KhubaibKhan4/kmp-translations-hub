package io.github.translationshub.idea

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import io.github.translationshub.core.Checks
import io.github.translationshub.core.Locale
import io.github.translationshub.core.PluralCategory
import io.github.translationshub.core.PluralsValue
import io.github.translationshub.core.Problem
import io.github.translationshub.core.ResourceKey

/**
 * The translation checks in the XML editor: on the default file, which locales a key is missing from; on a locale
 * file, its placeholder, plural-form, array-size and leftover-key problems.
 */
class TranslationsInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitFile(file: PsiFile) {
            val xml = file as? XmlFile ?: return
            val virtualFile = file.virtualFile ?: return
            val root = ResourceFolders.rootOf(virtualFile) ?: return
            val set = ResourceFolders.read(root)
            val here = set.sourceFiles.any { it.path == virtualFile.path }
            val locale = set.locales.firstOrNull { l -> set.filesFor(l).any { it.path == virtualFile.path } }
            if (!here && locale == null) return
            val problems = Checks.run(set)

            if (here) {
                problems.filterIsInstance<Problem.Missing>().groupBy { it.key }.forEach { (key, missing) ->
                    if (set.sourceFile(key)?.path != virtualFile.path) return@forEach
                    val element = nameElement(xml, key) ?: return@forEach
                    val tags = missing.map { it.locale.tag }
                    holder.registerProblem(
                        element,
                        "Missing in ${tags.take(5).joinToString()}${if (tags.size > 5) " and ${tags.size - 5} more" else ""}",
                        ProblemHighlightType.WEAK_WARNING,
                        OpenInTranslationsFix(key, missing.first().locale),
                    )
                }
            }
            for (problem in problems) {
                if (problem is Problem.Missing) continue
                val problemLocale = problem.locale
                val mine = if (problemLocale == null) here && set.sourceFile(problem.key)?.path == virtualFile.path
                else problemLocale == locale && set.translationFile(problem.key, problemLocale)?.path == virtualFile.path
                if (!mine) continue
                val element = nameElement(xml, problem.key) ?: continue
                val fixes = buildList<LocalQuickFix> {
                    if (problem is Problem.MissingPluralCategories) add(AddPluralFormsFix(problem.key, problem.locale, problem.missing))
                    if (problem is Problem.NotInSource) add(DeleteKeyFix(problem.key))
                    add(OpenInTranslationsFix(problem.key, problem.locale))
                }
                holder.registerProblem(element, problem.message, *fixes.toTypedArray())
            }
        }
    }

    private fun nameElement(file: XmlFile, key: ResourceKey) = StringsXmlWriter.findTag(file, key)?.getAttribute("name")?.valueElement

    /** Adds the missing forms, starting from the `other` text, so the translator only has to adjust them. */
    private class AddPluralFormsFix(val key: ResourceKey, val locale: Locale?, val missing: Set<PluralCategory>) : LocalQuickFix {
        override fun getFamilyName() = "Add missing plural forms"
        override fun getName() = "Add ${missing.joinToString { it.xmlName }}"
        override fun startInWriteAction() = true
        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val file = descriptor.psiElement.containingFile as? XmlFile ?: return
            val set = ResourceFolders.read(ResourceFolders.rootOf(file.virtualFile) ?: return)
            val value = (if (locale == null) set.source(key) else set.translation(key, locale))?.value as? PluralsValue ?: return
            val template = value.items[PluralCategory.OTHER] ?: value.items.values.firstOrNull() ?: ""
            missing.sorted().forEach { StringsXmlWriter.setPluralItem(file, key.name, it, template) }
        }
    }

    private class DeleteKeyFix(val key: ResourceKey) : LocalQuickFix {
        override fun getFamilyName() = "Delete key left behind"
        override fun startInWriteAction() = true
        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val file = descriptor.psiElement.containingFile as? XmlFile ?: return
            StringsXmlWriter.delete(file, key)
        }
    }

    /** Switches to the Translations tab with the cell selected. */
    private class OpenInTranslationsFix(val key: ResourceKey, val locale: Locale?) : LocalQuickFix {
        override fun getFamilyName() = "Open in the Translations tab"
        override fun startInWriteAction() = false
        override fun availableInBatchMode() = false
        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val file = descriptor.psiElement.containingFile?.virtualFile ?: return
            val manager = FileEditorManager.getInstance(project)
            manager.openFile(file, true)
            (manager as? FileEditorManagerEx)?.setSelectedEditor(file, TranslationsEditorProvider.TYPE_ID)
            manager.getEditors(file).filterIsInstance<TranslationsEditor>().firstOrNull()?.select(Cell(key, locale))
        }
    }
}
