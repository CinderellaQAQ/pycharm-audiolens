package audiolens.pycharm.remote

import audiolens.pycharm.diagnostics.AudioLensDiagnostics
import audiolens.pycharm.editor.AudioLensFileType
import audiolens.pycharm.navigation.AudioPathReference
import audiolens.pycharm.settings.AudioLensSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

object RemoteAudioSupport {
    fun provider(project: Project): SftpRemoteAudioProvider? = runCatching {
        project.getService(SftpRemoteAudioProvider::class.java)
    }.getOrNull()

    fun listServers(project: Project?): List<RemoteSftpServer> =
        project?.takeUnless { it.isDisposed }?.let(::provider)?.let { runCatching(it::listServers).getOrDefault(emptyList()) }
            ?: emptyList()

    fun canNavigate(project: Project, reference: AudioPathReference): Boolean {
        val settings = AudioLensSettings.getInstance().state
        return settings.remoteSftpEnabled &&
            settings.remoteSftpServerId.isNotBlank() &&
            provider(project) != null &&
            extension(reference.pathText) in AudioLensFileType.SUPPORTED_EXTENSIONS
    }

    fun open(project: Project, reference: AudioPathReference) {
        val settings = AudioLensSettings.getInstance().state
        val error = validate(project, reference, settings)
        if (error != null) {
            AudioLensDiagnostics.reportRemoteFailure(project, reference.pathText, error)
            return
        }
        val provider = provider(project)!!
        val serverId = settings.remoteSftpServerId
        val maximumBytes = settings.maxFileSizeMB.toLong() * MEBIBYTE
        val remotePath = try {
            RemotePathMapping.map(reference.pathText, settings.remotePathPrefix, settings.remoteBasePath)
        } catch (mappingError: IllegalArgumentException) {
            AudioLensDiagnostics.reportRemoteFailure(
                project,
                reference.pathText,
                RemoteAudioException(
                    RemoteAudioFailureKind.CONFIGURATION,
                    mappingError.message ?: "The remote path does not match the configured mapping.",
                    "Remote path mapping rejected the requested path.",
                    mappingError,
                ),
            )
            return
        }
        object : Task.Backgroundable(project, "Downloading AudioLens audio", true) {
            private var localFile: Path? = null

            override fun run(indicator: ProgressIndicator) {
                localFile = project.service<RemoteAudioCacheService>().fetch(
                    provider,
                    serverId,
                    remotePath,
                    maximumBytes,
                    indicator,
                )
            }

            override fun onSuccess() {
                val path = localFile ?: return
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                if (virtualFile == null) {
                    onThrowable(
                        RemoteAudioException(
                            RemoteAudioFailureKind.DOWNLOAD,
                            "PyCharm could not open the downloaded audio cache file.",
                            "The downloaded cache file was not visible in the local file system.",
                        ),
                    )
                    return
                }
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
                AudioLensDiagnostics.recordRemoteSuccess(reference.pathText, virtualFile.length)
            }

            override fun onThrowable(error: Throwable) {
                val remoteError = error as? RemoteAudioException ?: RemoteAudioException(
                    RemoteAudioFailureKind.DOWNLOAD,
                    "AudioLens could not download the remote audio file.",
                    "Remote download failed (${error.javaClass.simpleName}).",
                    error,
                )
                AudioLensDiagnostics.reportRemoteFailure(project, reference.pathText, remoteError)
            }
        }.queue()
    }

    fun testConnection(
        project: Project,
        serverId: String,
        onComplete: (RemoteConnectionTestResult) -> Unit,
    ) {
        val provider = provider(project)
        if (provider == null) {
            onComplete(RemoteConnectionTestResult(false, "PyCharm's FTP/SFTP/WebDAV Connectivity plugin is unavailable."))
            return
        }
        object : Task.Backgroundable(project, "Testing AudioLens SFTP connection", true) {
            private var result = RemoteConnectionTestResult(false, "The SFTP test did not finish.")

            override fun run(indicator: ProgressIndicator) {
                result = try {
                    provider.testConnection(serverId, indicator)
                } catch (error: RemoteAudioException) {
                    AudioLensDiagnostics.recordRemoteFailure("test.wav", error)
                    RemoteConnectionTestResult(false, error.userMessage)
                }
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater { onComplete(result) }
            }
        }.queue()
    }

    private fun validate(
        project: Project,
        reference: AudioPathReference,
        settings: AudioLensSettings.SettingsState,
    ): RemoteAudioException? = when {
        reference.arkOffset != null || extension(reference.pathText) == "ark" -> RemoteAudioException(
            RemoteAudioFailureKind.UNSUPPORTED,
            "Remote SFTP Ark files are not supported. Download the Ark file locally, then open it with AudioLens.",
            "Remote Ark access is intentionally unsupported.",
        )
        !settings.remoteSftpEnabled -> RemoteAudioException(
            RemoteAudioFailureKind.CONFIGURATION,
            "Remote SFTP audio is disabled. Enable it in Settings | Tools | AudioLens.",
            "Remote SFTP support was disabled.",
        )
        settings.remoteSftpServerId.isBlank() -> RemoteAudioException(
            RemoteAudioFailureKind.CONFIGURATION,
            "Choose a PyCharm SFTP configuration in AudioLens settings first.",
            "No Web Deployment server ID was configured.",
        )
        provider(project) == null -> RemoteAudioException(
            RemoteAudioFailureKind.CONFIGURATION,
            "PyCharm's FTP/SFTP/WebDAV Connectivity plugin is unavailable or disabled.",
            "The optional Web Deployment integration service was unavailable.",
        )
        else -> null
    }

    private fun extension(path: String): String = path.substringAfterLast('.', "").lowercase()

    private const val MEBIBYTE = 1024L * 1024L
}
