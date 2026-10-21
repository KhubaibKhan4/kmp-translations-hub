package io.github.translationshub.idea

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** Adds the "Translations" tab next to the text editor of every XML file in `composeResources/values…`. */
class TranslationsEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile) = ResourceFolders.rootOf(file) != null

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        TranslationsEditor(project, file, ResourceFolders.rootOf(file)!!)

    override fun getEditorTypeId() = TYPE_ID

    override fun getPolicy() = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR

    companion object {
        const val TYPE_ID = "kmp-translations-hub"
    }
}
