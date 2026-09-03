package audiolens.pycharm.navigation

import audiolens.pycharm.ark.ArkSelectionService
import audiolens.pycharm.ark.ArkSupport
import audiolens.pycharm.remote.RemoteAudioSupport
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

object AudioPathOpener {
    fun resolve(reference: AudioPathReference, contextFile: VirtualFile?, project: Project): ResolvedAudioPath? =
        AudioPathResolver.resolve(
            reference,
            contextFile?.takeIf { it.isInLocalFileSystem }?.let { Path.of(it.path) },
            project.basePath?.let(Path::of),
        )

    fun open(project: Project, reference: AudioPathReference, resolved: ResolvedAudioPath?) {
        if (resolved == null && RemoteAudioSupport.canNavigate(project, reference)) {
            RemoteAudioSupport.open(project, reference)
            return
        }
        try {
            val target = resolved ?: throw IllegalArgumentException(
                "Audio file not found. Relative paths are checked from the current file and the project root: ${reference.pathText}",
            )
            target.arkOffset?.let { ArkSupport.parseEntry(target.path, it) }
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target.path)
                ?: throw IllegalArgumentException("PyCharm could not open the audio file: ${target.path.fileName}")
            if (target.arkOffset != null) {
                project.service<ArkSelectionService>().remember(target.path, target.arkOffset)
            }
            val manager = FileEditorManager.getInstance(project)
            if (target.arkOffset != null && manager.isFileOpen(virtualFile)) manager.closeFile(virtualFile)
            manager.openFile(virtualFile, true)
        } catch (error: Throwable) {
            Messages.showWarningDialog(project, error.message ?: "AudioLens could not open this audio path.", "AudioLens")
        }
    }
}
