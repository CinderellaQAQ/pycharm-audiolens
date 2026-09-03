package audiolens.pycharm.remote

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task

class ClearRemoteAudioCacheAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        object : Task.Backgroundable(project, "Clearing AudioLens remote cache", false) {
            private var removed = RemoteCacheStats(0, 0)

            override fun run(indicator: ProgressIndicator) {
                removed = project.service<RemoteAudioCacheService>().clear()
            }

            override fun onSuccess() {
                NotificationGroupManager.getInstance().getNotificationGroup("AudioLens")
                    .createNotification(
                        "AudioLens remote cache cleared",
                        "Removed ${removed.fileCount} cached audio file(s). They can be downloaded again when needed.",
                        NotificationType.INFORMATION,
                    )
                    .notify(project)
            }
        }.queue()
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }
}
