package io.github.translationshub.idea

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import io.github.translationshub.core.Locale

/** Picks a locale to add: the common app languages, or any BCP 47 tag typed in (`pt-BR`, `sr-Latn`). */
object LocaleChooser {

    /** Languages app stores and translation services cover best, most requested first. */
    val COMMON: List<String> = listOf(
        "es", "fr", "de", "pt-BR", "it", "ja", "ko", "zh-Hans", "zh-Hant", "ar", "hi", "ur", "ru", "tr", "id", "vi",
        "th", "pl", "nl", "sv", "uk", "he", "fa", "bn", "ms", "ro", "cs", "el", "hu", "da", "fi", "nb", "pt-PT",
    )

    fun choose(project: Project, existing: List<Locale>): Locale? {
        val options = COMMON.mapNotNull(Locale::fromTag).filterNot { it in existing }.map { "${it.tag} · ${it.displayName}" }
        val picked = Messages.showEditableChooseDialog(
            "Language tag, e.g. pt-BR or sr-Latn:", "Add Locale", null, options.toTypedArray(), options.firstOrNull() ?: "",
            object : InputValidatorEx {
                override fun checkInput(inputString: String) = getErrorText(inputString) == null
                override fun canClose(inputString: String) = checkInput(inputString)
                override fun getErrorText(inputString: String): String? {
                    val locale = parse(inputString) ?: return "Not a language tag"
                    return if (locale in existing) "${locale.tag} already exists" else null
                }
            },
        ) ?: return null
        return parse(picked)
    }

    /** `pt-BR · Portuguese (Brazil)` or just `pt-BR`. */
    fun parse(text: String): Locale? = Locale.fromTag(text.substringBefore('·').trim())
}
