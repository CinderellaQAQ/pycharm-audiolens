package audiolens.pycharm.remote

import com.intellij.openapi.progress.ProgressIndicator
import java.nio.file.Path

data class RemoteSftpServer(
    val id: String,
    val displayName: String,
)

data class RemoteFileMetadata(
    val size: Long,
    val lastModified: Long,
)

data class RemoteDownloadResult(
    val metadata: RemoteFileMetadata,
    val downloaded: Boolean,
)

data class RemoteConnectionTestResult(
    val successful: Boolean,
    val message: String,
)

/**
 * Implemented only when PyCharm's optional Web Deployment plugin is available.
 * Keeping the implementation behind this interface prevents SFTP classes from
 * being loaded for users who only need local AudioLens support.
 */
interface SftpRemoteAudioProvider {
    fun listServers(): List<RemoteSftpServer>

    fun testConnection(serverId: String, indicator: ProgressIndicator): RemoteConnectionTestResult

    fun downloadIfChanged(
        serverId: String,
        remotePath: String,
        cachedMetadata: RemoteFileMetadata?,
        destination: Path,
        maximumBytes: Long,
        indicator: ProgressIndicator,
    ): RemoteDownloadResult
}
