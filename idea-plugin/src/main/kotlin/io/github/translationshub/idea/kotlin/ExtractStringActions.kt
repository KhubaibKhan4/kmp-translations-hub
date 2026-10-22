package io.github.translationshub.idea.kotlin

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiElementVisitor
import io.github.translationshub.idea.KeyNameValidator
import io.github.translationshub.idea.ProLicense
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtVisitorVoid

private const val FEATURE = "Extracting strings to composeResources"

/** Alt+Enter on a string in Compose UI code: "Extract to composeResources". */
class ExtractStringIntention : PsiElementBaseIntentionAction() {

    override fun getFamilyName() = "Extract to composeResources"
    override fun getText() = familyName
    override fun startInWriteAction() = false

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val candidate = ExtractString.candidate(element) ?: return false
        // Where the inspection already offers the same fix, Alt+Enter would list it twice.
        return !(ExtractString.isComposeCallArgument(candidate.literal) && inspectionEnabled(project, element))
    }

    private fun inspectionEnabled(project: Project, element: PsiElement): Boolean {
        val key = HighlightDisplayKey.find(HardcodedComposeTextInspection.SHORT_NAME) ?: return false
        return InspectionProjectProfileManager.getInstance(project).currentProfile.isToolEnabled(key, element)
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val candidate = ExtractString.candidate(element) ?: return
        ProLicense.runOrUpsell(project, FEATURE) {
            val suggested = ExtractString.suggestName(candidate)
            val name = if (ApplicationManager.getApplication().isUnitTestMode) suggested
            else Messages.showInputDialog(
                project, "Key for \"${candidate.text}\":", "Extract to composeResources", null, suggested,
                KeyNameValidator { !ExtractString.isTaken(candidate, it) },
            ) ?: return@runOrUpsell
            ExtractString.extract(project, candidate, name)
        }
    }
}

/**
 * Text passed straight to Compose UI calls (`Text("Hello")`, `label = "Name"`). Run it over the whole project with
 * Code → Inspect Code to extract every hardcoded string at once; keys are named from the text.
 */
class HardcodedComposeTextInspection : LocalInspectionTool() {

    companion object {
        const val SHORT_NAME = "HardcodedComposeText"
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : KtVisitorVoid() {
        override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
            if (!ExtractString.isComposeCallArgument(expression)) return
            if (ExtractString.candidate(expression) == null) return
            holder.registerProblem(expression, "Hardcoded text in Compose UI", ProblemHighlightType.WEAK_WARNING, ExtractFix())
        }
    }

    private class ExtractFix : LocalQuickFix {
        override fun getFamilyName() = "Extract to composeResources"
        override fun startInWriteAction() = false
        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val candidate = ExtractString.candidate(descriptor.psiElement) ?: return
            ProLicense.runOrUpsell(project, FEATURE) { ExtractString.extract(project, candidate) }
        }
    }
}
