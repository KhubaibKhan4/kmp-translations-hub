package io.github.translationshub.idea

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import io.github.translationshub.core.ArrayValue
import io.github.translationshub.core.PluralCategory
import io.github.translationshub.core.PluralsValue
import io.github.translationshub.core.ResourceKind
import io.github.translationshub.core.ResourceValue
import io.github.translationshub.core.TextValue
import javax.swing.JComponent

/** Name, kind and default text of a new key. */
class AddKeyDialog(
    project: Project,
    private val format: io.github.translationshub.core.ResourceFormat = io.github.translationshub.core.ResourceFormat.COMPOSE,
    private val isFree: (String, ResourceKind) -> Boolean,
) : DialogWrapper(project) {

    private val nameField = JBTextField(30)
    private val kindBox = ComboBox(ResourceKind.entries.toTypedArray()).apply {
        renderer = listRenderer { kind ->
            when (kind) {
                ResourceKind.STRING -> "String"
                ResourceKind.PLURALS -> "Plurals"
                ResourceKind.ARRAY -> "String array"
                null -> ""
            }
        }
    }
    private val textField = JBTextField(30)

    val keyName: String get() = nameField.text.trim()
    val kind: ResourceKind get() = kindBox.item

    /** The default text: the string, the plural `other` form, or the first array item. */
    val value: ResourceValue
        get() = when (kind) {
            ResourceKind.STRING -> TextValue(textField.text)
            ResourceKind.PLURALS -> PluralsValue(mapOf(PluralCategory.OTHER to textField.text))
            ResourceKind.ARRAY -> ArrayValue(listOf(textField.text))
        }

    init {
        title = "Add Key"
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Name:", nameField)
        .addLabeledComponent("Kind:", kindBox)
        .addLabeledComponent("Default text:", textField)
        .panel

    override fun getPreferredFocusedComponent() = nameField

    override fun doValidate(): ValidationInfo? = when {
        !TranslationsController.isValidKeyName(keyName, format) -> ValidationInfo(TranslationsController.keyNameRule(format), nameField)
        !isFree(keyName, kind) -> ValidationInfo("$keyName already exists", nameField)
        else -> null
    }
}
