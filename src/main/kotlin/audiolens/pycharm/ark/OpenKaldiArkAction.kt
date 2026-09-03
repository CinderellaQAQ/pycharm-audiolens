package audiolens.pycharm.ark

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path

class OpenKaldiArkAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val input = Messages.showInputDialog(
            project,
            "Enter a Kaldi WAV ark path and byte offset, for example /data/wav.ark:23252",
            "AudioLens: Open Kaldi WAV from Ark",
            null,
        ) ?: return
        try {
            val parsed = ArkSupport.parsePathAndOffset(input)
                ?: throw IllegalArgumentException("Use an .ark path followed by :offset.")
            var path = parsed.first
            if (!path.isAbsolute) path = Path.of(project.basePath ?: ".").resolve(path).normalize()
            require(Files.isRegularFile(path)) { "Ark file does not exist: $path" }
            val offset = parsed.second ?: Messages.showInputDialog(
                project, "Enter the byte offset of the RIFF/WAVE entry.", "AudioLens: Kaldi WAV Ark Offset", null,
            )?.trim()?.toLongOrNull() ?: return
            ArkSupport.parseEntry(path, offset)
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                ?: throw IllegalArgumentException("Cannot open ark file: $path")
            project.service<ArkSelectionService>().remember(path, offset)
            val manager = FileEditorManager.getInstance(project)
            if (manager.isFileOpen(virtualFile)) manager.closeFile(virtualFile)
            manager.openFile(virtualFile, true)
        } catch (error: Throwable) {
            Messages.showWarningDialog(project, error.message ?: "Cannot open the ark entry.", "AudioLens")
        }
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }
}
