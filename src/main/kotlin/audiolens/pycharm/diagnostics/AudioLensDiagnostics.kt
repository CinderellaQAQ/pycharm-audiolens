package audiolens.pycharm.diagnostics

import audiolens.pycharm.audio.AudioLensFfmpegException
import audiolens.pycharm.audio.FfmpegResolver
import audiolens.pycharm.compat.ProjectTrust
import audiolens.pycharm.editor.AudioLensFileType
import audiolens.pycharm.remote.RemoteAudioException
import audiolens.pycharm.remote.RemoteAudioCacheService
import audiolens.pycharm.remote.RemoteAudioSupport
import audiolens.pycharm.settings.AudioLensConfigurable
import audiolens.pycharm.settings.AudioLensSettings
import audiolens.pycharm.web.AudioSource
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefApp
import java.time.Instant

object AudioLensDiagnostics {
    const val NOTIFICATION_GROUP = "AudioLens"
    private const val PLUGIN_ID = "io.github.simzhou.audiolens"

    fun reportFfmpegFailure(project: Project, source: AudioSource, error: AudioLensFfmpegException) {
        val diagnostics = service<AudioLensDiagnosticsService>()
        if (!diagnostics.recordFailure(error, source)) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("AudioLens could not process the audio", error.userMessage, NotificationType.ERROR)
                .addAction(NotificationAction.create("Copy Diagnostics") { _, notification ->
                    copy(project)
                    notification.expire()
                })
                .addAction(NotificationAction.create("Configure FFmpeg") { _, notification ->
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, AudioLensConfigurable::class.java)
                    notification.expire()
                })
                .notify(project)
        }
    }

    fun reportRemoteFailure(project: Project, sourcePath: String, error: RemoteAudioException) {
        if (!recordRemoteFailure(sourcePath, error)) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("AudioLens could not open remote audio", error.userMessage, NotificationType.ERROR)
                .addAction(NotificationAction.create("Copy Diagnostics") { _, notification ->
                    copy(project)
                    notification.expire()
                })
                .addAction(NotificationAction.create("Configure SFTP") { _, notification ->
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, AudioLensConfigurable::class.java)
                    notification.expire()
                })
                .notify(project)
        }
    }

    fun recordRemoteFailure(sourcePath: String, error: RemoteAudioException): Boolean =
        service<AudioLensDiagnosticsService>().recordRemoteFailure(sourcePath, error)

    fun recordRemoteSuccess(sourcePath: String, size: Long) {
        service<AudioLensDiagnosticsService>().recordRemoteSuccess(sourcePath, size)
    }

    fun copy(project: Project?) {
        CopyPasteManager.copyTextToClipboard(buildReport(project))
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "AudioLens diagnostics copied",
                "The report excludes audio content, full audio paths, and project paths.",
                NotificationType.INFORMATION,
            )
            .notify(project)
    }

    internal fun buildReport(project: Project?): String {
        val app = ApplicationInfo.getInstance()
        val settings = AudioLensSettings.getInstance().state
        val diagnostics = service<AudioLensDiagnosticsService>()
        val failure = diagnostics.failure()
        val test = diagnostics.test()
        val remote = diagnostics.remoteOperation()
        val currentFile = project?.takeUnless { it.isDisposed }?.let { FileEditorManager.getInstance(it).selectedFiles.firstOrNull() }
        val configured = settings.ffmpegPath.trim()
        val resolved = FfmpegResolver.resolve(configured.ifEmpty { null })
        val pluginVersion = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "development"
        val remoteCache = project?.takeUnless { it.isDisposed }?.service<RemoteAudioCacheService>()?.stats()

        return buildString {
            appendLine("AudioLens Diagnostics")
            appendLine("Generated: ${Instant.now()}")
            appendLine("Plugin: $pluginVersion")
            appendLine("IDE: ${app.fullApplicationName} (${app.build})")
            appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
            appendLine("Java: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
            appendLine("JCEF supported: ${JBCefApp.isSupported()}")
            appendLine("Project trusted: ${project?.takeUnless { it.isDisposed }?.let(ProjectTrust::isTrusted) ?: "not available"}")
            appendLine("Current audio: ${safeCurrentFile(currentFile?.name, currentFile?.length)}")
            appendLine("FFmpeg configured: ${configured.isNotEmpty()}")
            appendLine("FFmpeg resolved: ${resolved ?: "not found"}")
            appendLine("Remote SFTP enabled: ${settings.remoteSftpEnabled}")
            appendLine("Remote SFTP provider available: ${project?.takeUnless { it.isDisposed }?.let { RemoteAudioSupport.provider(it) != null } ?: "not available"}")
            appendLine("Remote SFTP server selected: ${settings.remoteSftpServerId.isNotBlank()}")
            appendLine("Remote path prefix configured: ${settings.remotePathPrefix.isNotBlank()}")
            appendLine("Remote base path configured: ${settings.remoteBasePath.isNotBlank()}")
            appendLine("Remote cache limit: ${settings.remoteCacheMaxMB} MB")
            appendLine("Remote cache: ${remoteCache?.let { "${it.fileCount} file(s), ${it.bytes} bytes" } ?: "not available"}")
            appendLine("Maximum source size: ${settings.maxFileSizeMB} MB")
            appendLine("Language: ${settings.language}")
            appendLine("Amplitude scale: ${settings.amplitudeScaleMode}")
            appendLine("Spectrogram: ${settings.windowFunction}, FFT ${settings.fftSize}, zero padding ${settings.zeroPaddingFactor}x")
            if (test != null) {
                appendLine()
                appendLine("Last FFmpeg test: ${test.timestamp}")
                appendLine("Test executable: ${test.path ?: "not found"}")
                appendLine("Test result: ${test.message}")
            }
            if (failure != null) {
                appendLine()
                appendLine("Last FFmpeg failure: ${failure.timestamp}")
                appendLine("Category: ${failure.kind}")
                appendLine("Operation: ${failure.operation}")
                appendLine("Audio: ${failure.sourceName} (${failure.sourceExtension}, ${failure.sourceSize} bytes, source=${failure.sourceKind ?: "file"})")
                appendLine("User message: ${failure.message}")
                appendLine("Technical detail:")
                appendLine(failure.technicalDetail)
            }
            if (remote != null) {
                appendLine()
                appendLine("Last SFTP operation: ${remote.timestamp}")
                appendLine("Successful: ${remote.successful}")
                appendLine("Category: ${remote.kind}")
                appendLine("Audio: ${remote.sourceName} (${remote.sourceExtension}, ${remote.sourceSize?.let { "$it bytes" } ?: "size unavailable"})")
                appendLine("User message: ${remote.message}")
                appendLine("Technical detail:")
                appendLine(remote.technicalDetail)
            }
        }.trimEnd()
    }

    private fun safeCurrentFile(name: String?, size: Long?): String {
        if (name == null || name.substringAfterLast('.', "").lowercase() !in AudioLensFileType.SUPPORTED_EXTENSIONS) return "none"
        return "$name (${size ?: 0} bytes)"
    }
}
