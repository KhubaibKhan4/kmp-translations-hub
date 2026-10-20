package io.github.translationshub.idea

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * Runs edits to one or more `strings.xml` files as a single undoable command: renaming a key in 15 locale files is
 * one Undo, from the Translations editor or any of those files.
 */
object TranslationsWriteCommand {

    fun run(project: Project, name: String, files: Collection<PsiFile>, edit: () -> Unit) {
        WriteCommandAction.writeCommandAction(project, *files.toTypedArray())
            .withName(name)
            .run<RuntimeException> {
                edit()
                // Push PSI edits into the documents now, so the table re-reads what was just written.
                val documents = PsiDocumentManager.getInstance(project)
                files.mapNotNull(documents::getDocument).forEach(documents::doPostponedOperationsAndUnblockDocument)
            }
    }
}
