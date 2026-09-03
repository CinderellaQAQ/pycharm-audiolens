package audiolens.pycharm.diagnostics

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class CopyAudioLensDiagnosticsAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        AudioLensDiagnostics.copy(event.project)
    }
}
