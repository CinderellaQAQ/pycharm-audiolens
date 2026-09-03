package audiolens.pycharm.editor

import audiolens.pycharm.compat.ProjectTrust
import audiolens.pycharm.diagnostics.AudioLensDiagnostics
import audiolens.pycharm.remote.RemoteAudioCacheService
import audiolens.pycharm.settings.AudioLensSettings
import audiolens.pycharm.web.AudioSource
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Loads a Web Deployment RemoteVirtualFile into the local cache, then hosts AudioLens in-place. */
class AudioLensRemoteFileEditor(
    private val project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {
    private val disposed = AtomicBoolean()
    private val changes = PropertyChangeSupport(this)
    private val status = JBLabel("Preparing remote audio…", SwingConstants.CENTER)
    private val panel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(24)
        add(status, BorderLayout.CENTER)
    }
    @Volatile private var indicator: ProgressIndicator? = null
    @Volatile private var delegate: AudioLensFileEditor? = null
    private var localPath: Path? = null

    init {
        if (!ProjectTrust.isTrusted(project)) {
            showMessage("This project is not trusted; AudioLens will not read remote audio content.")
        } else {
            loadRemoteContent()
        }
    }

    private fun loadRemoteContent() {
        object : Task.Backgroundable(project, "Preparing remote AudioLens audio", true) {
            override fun run(progress: ProgressIndicator) {
                indicator = progress
                progress.text = "Copying downloaded remote audio into the AudioLens cache"
                val maximumBytes = AudioLensSettings.getInstance().state.maxFileSizeMB.coerceIn(16, 4096) * MEBIBYTE
                localPath = project.service<RemoteAudioCacheService>().importVirtualFile(file, maximumBytes, progress)
            }

            override fun onSuccess() {
                indicator = null
                if (disposed.get() || project.isDisposed || !file.isValid) return
                val path = localPath ?: return
                runCatching {
                    AudioLensFileEditor(project, file, AudioSource(path, file.name))
                }.onSuccess { editor ->
                    showEditor(editor)
                    AudioLensDiagnostics.recordRemoteSuccess(
                        file.path,
                        runCatching { java.nio.file.Files.size(path) }.getOrDefault(0L),
                    )
                }.onFailure { error ->
                    showMessage(error.message ?: "AudioLens could not prepare the remote audio file.")
                }
            }

            override fun onThrowable(error: Throwable) {
                indicator = null
                if (!disposed.get()) {
                    showMessage(error.message ?: "AudioLens could not prepare the remote audio file.")
                }
            }
        }.queue()
    }

    private fun showEditor(editor: AudioLensFileEditor) {
        if (disposed.get()) {
            Disposer.dispose(editor)
            return
        }
        delegate = editor
        Disposer.register(this, editor)
        panel.border = null
        panel.removeAll()
        panel.add(editor.component, BorderLayout.CENTER)
        panel.revalidate()
        panel.repaint()
    }

    private fun showMessage(message: String) {
        if (disposed.get()) return
        status.text = "<html><h2>AudioLens</h2><p>${escape(message)}</p></html>"
        panel.revalidate()
        panel.repaint()
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = delegate?.preferredFocusedComponent ?: panel
    override fun getName(): String = "AudioLens"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = delegate?.setState(state) ?: Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = !disposed.get() && file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = changes.addPropertyChangeListener(listener)
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = changes.removePropertyChangeListener(listener)
    override fun getCurrentLocation(): FileEditorLocation? = delegate?.currentLocation

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        indicator?.cancel()
        indicator = null
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    companion object {
        private const val MEBIBYTE = 1024L * 1024L
    }
}
