package audiolens.pycharm.editor

import audiolens.pycharm.web.AudioLensSession
import audiolens.pycharm.web.AudioLensSessionRegistry
import audiolens.pycharm.web.AudioSource
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JPanel

class AudioLensFileEditor(
    private val project: Project,
    private val file: VirtualFile,
    source: AudioSource,
) : UserDataHolderBase(), FileEditor {
    private val changes = PropertyChangeSupport(this)
    private val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val registry = ApplicationManager.getApplication().getService(AudioLensSessionRegistry::class.java)
    private val log = Logger.getInstance(AudioLensFileEditor::class.java)
    private val panel = JPanel(BorderLayout()).apply { add(browser.component, BorderLayout.CENTER) }
    private val disposed = AtomicBoolean()
    private val session: AudioLensSession
    private val token: String

    init {
        lateinit var activeSession: AudioLensSession
        query.addHandler { request ->
            activeSession.handle(request)
            null
        }
        activeSession = AudioLensSession(project, source, query.inject("message")) { json -> dispatch(json) }
        session = activeSession
        token = registry.register(session)
        session.token = token
        Disposer.register(this, query)
        Disposer.register(this, browser)

        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path == file.path }) session.sourceChanged()
            }
        })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(LafManagerListener.TOPIC, LafManagerListener {
            reloadPage()
        })
        reloadPage()
    }

    private fun reloadPage() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val url = runCatching { registry.url(token, "index.html") }.getOrElse { error ->
                log.warn("AudioLens could not resolve its local web UI URL", error)
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater {
                if (!disposed.get() && !project.isDisposed && file.isValid) {
                    runCatching { browser.loadURL(url) }
                        .onFailure { log.warn("AudioLens ignored a page load after its editor became unavailable", it) }
                }
            }
        }
    }

    private fun dispatch(json: String) {
        val safeJson = json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        ApplicationManager.getApplication().invokeLater {
            if (!disposed.get() && !project.isDisposed && file.isValid) {
                runCatching {
                    browser.cefBrowser.executeJavaScript(
                        "window.__audioLensReceive && window.__audioLensReceive($safeJson);",
                        browser.cefBrowser.url,
                        0,
                    )
                }.onFailure { log.warn("AudioLens ignored a browser callback after its editor became unavailable", it) }
            }
        }
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = browser.component
    override fun getName(): String = "AudioLens"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = !disposed.get() && file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = changes.addPropertyChangeListener(listener)
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = changes.removePropertyChangeListener(listener)
    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        registry.unregister(token)
        session.close()
    }
}
