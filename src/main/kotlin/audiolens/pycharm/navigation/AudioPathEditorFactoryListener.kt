package audiolens.pycharm.navigation

import audiolens.pycharm.remote.RemoteAudioSupport

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Key
import java.awt.Cursor
import java.awt.event.MouseEvent

class AudioPathEditorFactoryListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        if (editor.project == null || editor.isOneLineMode) return
        val support = AudioPathMouseSupport(editor)
        editor.putUserData(SUPPORT_KEY, support)
        editor.addEditorMouseListener(support)
        editor.addEditorMouseMotionListener(support)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        event.editor.getUserData(SUPPORT_KEY)?.dispose()
        event.editor.putUserData(SUPPORT_KEY, null)
    }

    private companion object {
        val SUPPORT_KEY = Key.create<AudioPathMouseSupport>("audiolens.audioPathMouseSupport")
    }
}

private class AudioPathMouseSupport(private val editor: Editor) : EditorMouseListener, EditorMouseMotionListener {
    private var highlighter: RangeHighlighter? = null
    private var originalCursor: Cursor? = null
    private var target: HoverTarget? = null

    override fun mouseMoved(event: EditorMouseEvent) {
        if (!isNavigationGesture(event) || event.area != EditorMouseEventArea.EDITING_AREA || !event.isOverText) {
            clearHover()
            return
        }
        val project = editor.project ?: return clearHover()
        val reference = AudioPathReferenceParser.findAt(editor.document.charsSequence, event.offset) ?: return clearHover()
        val contextFile = FileDocumentManager.getInstance().getFile(editor.document)
        val resolved = AudioPathOpener.resolve(reference, contextFile, project)
        if (resolved == null && !RemoteAudioSupport.canNavigate(project, reference)) return clearHover()
        updateHover(HoverTarget(reference, resolved))
    }

    override fun mouseClicked(event: EditorMouseEvent) {
        if (event.mouseEvent.button != MouseEvent.BUTTON1 || !isNavigationGesture(event) || !event.isOverText) return
        val project = editor.project ?: return
        val reference = AudioPathReferenceParser.findAt(editor.document.charsSequence, event.offset) ?: return
        val contextFile = FileDocumentManager.getInstance().getFile(editor.document)
        val resolved = AudioPathOpener.resolve(reference, contextFile, project)
        event.consume()
        AudioPathOpener.open(project, reference, resolved)
    }

    override fun mouseExited(event: EditorMouseEvent) = clearHover()

    fun dispose() {
        editor.removeEditorMouseListener(this)
        editor.removeEditorMouseMotionListener(this)
        clearHover()
    }

    private fun updateHover(next: HoverTarget) {
        if (target == next && highlighter?.isValid == true) return
        clearHover()
        target = next
        highlighter = editor.markupModel.addRangeHighlighter(
            CodeInsightColors.HYPERLINK_ATTRIBUTES,
            next.reference.startOffset,
            next.reference.endOffset,
            HighlighterLayer.HYPERLINK,
            HighlighterTargetArea.EXACT_RANGE,
        )
        originalCursor = editor.contentComponent.cursor
        editor.contentComponent.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private fun clearHover() {
        highlighter?.let { if (it.isValid) editor.markupModel.removeHighlighter(it) }
        highlighter = null
        target = null
        originalCursor?.let { editor.contentComponent.cursor = it }
        originalCursor = null
    }

    private fun isNavigationGesture(event: EditorMouseEvent): Boolean =
        event.mouseEvent.isControlDown || event.mouseEvent.isMetaDown

    private data class HoverTarget(val reference: AudioPathReference, val resolved: ResolvedAudioPath?)
}
