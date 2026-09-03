package audiolens.pycharm.remote

import audiolens.pycharm.navigation.AudioPathReferenceParser
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class OpenRemoteAudioAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val path = Messages.showInputDialog(
            project,
            "Enter an audio path available through the SFTP configuration selected in AudioLens settings:",
            "Open Remote Audio",
            Messages.getQuestionIcon(),
        )?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val reference = AudioPathReferenceParser.parse(path)
        if (reference == null) {
            Messages.showWarningDialog(project, "The path does not have an AudioLens-supported extension.", "AudioLens SFTP")
            return
        }
        RemoteAudioSupport.open(project, reference)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }
}
