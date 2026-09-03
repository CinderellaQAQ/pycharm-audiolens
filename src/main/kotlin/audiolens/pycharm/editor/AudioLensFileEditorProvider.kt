package audiolens.pycharm.editor

import audiolens.pycharm.ark.ArkSelectionService
import audiolens.pycharm.ark.ArkSupport
import audiolens.pycharm.web.AudioSource
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefApp
import java.nio.file.Path

class AudioLensFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean =
        !file.isDirectory && file.extension?.lowercase() in AudioLensFileType.SUPPORTED_EXTENSIONS

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        if (!JBCefApp.isSupported()) {
            return AudioLensUnavailableEditor(file, "AudioLens requires the IDE's embedded Chromium (JCEF). It is unavailable in this runtime.")
        }
        if (!file.isInLocalFileSystem) {
            if (file.extension.equals("ark", ignoreCase = true)) {
                return AudioLensUnavailableEditor(
                    file,
                    "Remote SFTP Ark files are not supported. Download the Ark file locally before opening it with AudioLens.",
                )
            }
            return AudioLensRemoteFileEditor(project, file)
        }
        val path = Path.of(file.path)
        return try {
            val source = if (file.extension.equals("ark", ignoreCase = true)) {
                val remembered = project.service<ArkSelectionService>().offsetFor(path)
                val entered = remembered ?: Messages.showInputDialog(
                    project,
                    "${file.name} may be large. Enter the byte offset of the RIFF/WAVE entry to open.",
                    "AudioLens: Kaldi WAV Ark Offset",
                    null,
                )?.trim()?.toLongOrNull()
                if (entered == null) {
                    return AudioLensUnavailableEditor(file, "No Kaldi WAV ark offset was selected. Close and reopen the file to try again.")
                }
                val entry = ArkSupport.parseEntry(path, entered)
                AudioSource(path, file.nameWithoutExtension + "@$entered.wav", entry.offset, entry.size, "ark")
            } else {
                AudioSource(path, file.name)
            }
            AudioLensFileEditor(project, file, source)
        } catch (error: Throwable) {
            AudioLensUnavailableEditor(file, error.message ?: "AudioLens could not open this file.")
        }
    }

    override fun getEditorTypeId(): String = "audiolens.pycharm.editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
