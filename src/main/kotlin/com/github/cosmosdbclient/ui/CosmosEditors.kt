package com.github.cosmosdbclient.ui

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField

/**
 * Factory for embedded code editors. Uses a real IntelliJ editor (line numbers, folding,
 * and — for JSON — syntax highlighting) via [EditorTextField], which manages its own
 * lifecycle when removed from the component hierarchy.
 */
object CosmosEditors {

    private val jsonFileType: FileType
        get() = FileTypeManager.getInstance().getStdFileType("JSON")
            .takeUnless { it.name.equals("UNKNOWN", ignoreCase = true) }
            ?: PlainTextFileType.INSTANCE

    fun json(project: Project, text: String = "", viewer: Boolean = false): EditorTextField =
        multiline(project, text, jsonFileType, viewer)

    fun plain(project: Project, text: String = "", viewer: Boolean = false): EditorTextField =
        multiline(project, text, PlainTextFileType.INSTANCE, viewer)

    private fun multiline(project: Project, text: String, fileType: FileType, viewer: Boolean): EditorTextField {
        val field = object : EditorTextField(text, project, fileType) {
            override fun createEditor(): EditorEx {
                val editor = super.createEditor()
                editor.setVerticalScrollbarVisible(true)
                editor.setHorizontalScrollbarVisible(true)
                editor.isViewer = viewer
                with(editor.settings) {
                    isLineNumbersShown = true
                    isLineMarkerAreaShown = false
                    isFoldingOutlineShown = true
                    isUseSoftWraps = false
                    isCaretRowShown = !viewer
                    additionalColumnsCount = 2
                    additionalLinesCount = 1
                }
                return editor
            }
        }
        field.setOneLineMode(false)
        return field
    }
}
