package io.github.translationshub.idea.translate

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * Asks for a Marketplace rating once, after the third Translate Changes run that translated something with no
 * failures. Counted locally in the IDE's settings; nothing is sent anywhere.
 */
object RatingPrompt {
    const val RUNS_BEFORE_ASKING = 3
    const val REVIEWS_URL = "https://plugins.jetbrains.com/plugin/35376-kmp-translations-hub/reviews"

    /** Records a run and returns true the one time the prompt should be shown. */
    fun recordRun(state: TranslationSettings.State, summary: TranslateChanges.Summary): Boolean {
        if (state.ratingPromptShown) return false
        val succeeded = summary.written.values.sum() > 0 && summary.failed.isEmpty() && summary.errors.isEmpty()
        if (!succeeded) return false
        state.successfulRuns++
        if (state.successfulRuns < RUNS_BEFORE_ASKING) return false
        state.ratingPromptShown = true
        return true
    }

    fun show(project: Project) {
        NotificationGroupManager.getInstance().getNotificationGroup("KMP Translations Hub")
            .createNotification(
                "Enjoying KMP Translations Hub?",
                "A rating on JetBrains Marketplace helps other Compose Multiplatform developers find it. This is the only time we'll ask.",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring("Rate it") { BrowserUtil.browse(REVIEWS_URL) })
            .addAction(NotificationAction.createSimpleExpiring("No thanks") {})
            .notify(project)
    }
}
