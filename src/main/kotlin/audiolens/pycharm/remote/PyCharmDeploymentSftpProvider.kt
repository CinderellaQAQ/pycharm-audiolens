package audiolens.pycharm.remote

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.ssh.ForceDisconnectListener
import com.intellij.util.EventDispatcher
import com.jetbrains.plugins.webDeployment.ConnectionOwnerFactory
import com.jetbrains.plugins.webDeployment.config.AccessType
import com.jetbrains.plugins.webDeployment.config.Deployable
import com.jetbrains.plugins.webDeployment.config.FileTransferConfig
import com.jetbrains.plugins.webDeployment.config.GroupedServersConfigManager
import com.jetbrains.plugins.webDeployment.connections.RemoteConnection
import com.jetbrains.plugins.webDeployment.connections.RemoteConnectionManager
import org.apache.commons.vfs2.FileObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class PyCharmDeploymentSftpProvider(private val project: Project) : SftpRemoteAudioProvider {
    override fun listServers(): List<RemoteSftpServer> = manager().flattenedServers
        .asSequence()
        .filter { it.accessType == AccessType.SFTP }
        .mapNotNull { server ->
            val id = server.id ?: return@mapNotNull null
            RemoteSftpServer(id, server.getName() ?: id)
        }
        .sortedBy { it.displayName.lowercase() }
        .toList()

    override fun testConnection(serverId: String, indicator: ProgressIndicator): RemoteConnectionTestResult =
        withConnection(serverId, indicator) { connection ->
            indicator.text = "Checking the SFTP root"
            if (!connection.resolveRoot().exists()) {
                throw RemoteAudioException(
                    RemoteAudioFailureKind.CONNECTION,
                    "PyCharm connected, but the selected SFTP root is unavailable.",
                    "The configured SFTP root did not exist.",
                )
            }
            RemoteConnectionTestResult(true, "Connected to the selected PyCharm SFTP server.")
        }

    override fun downloadIfChanged(
        serverId: String,
        remotePath: String,
        cachedMetadata: RemoteFileMetadata?,
        destination: Path,
        maximumBytes: Long,
        indicator: ProgressIndicator,
    ): RemoteDownloadResult = withConnection(serverId, indicator) { connection ->
        indicator.text = "Checking remote audio"
        val remoteFile = connection.resolveFile(remotePath)
        try {
            if (!remoteFile.exists() || !remoteFile.isFile) {
                throw RemoteAudioException(
                    RemoteAudioFailureKind.NOT_FOUND,
                    "The audio file was not found on the selected SFTP server.",
                    "The requested SFTP file did not exist or was not a regular file.",
                )
            }
            val content = remoteFile.content
            val metadata = RemoteFileMetadata(content.size, content.lastModifiedTime)
            if (metadata.size > maximumBytes) {
                val actualMB = (metadata.size + MEBIBYTE - 1) / MEBIBYTE
                val maximumMB = maximumBytes / MEBIBYTE
                throw RemoteAudioException(
                    RemoteAudioFailureKind.TOO_LARGE,
                    "The remote audio is $actualMB MB, above AudioLens's $maximumMB MB source limit.",
                    "The SFTP file exceeded the configured maximum source size.",
                )
            }
            if (cachedMetadata == metadata) return@withConnection RemoteDownloadResult(metadata, false)

            indicator.text = "Downloading remote audio"
            Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                content.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        indicator.checkCanceled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (metadata.size > 0) indicator.fraction = copied.toDouble() / metadata.size.toDouble()
                        if (copied > maximumBytes) {
                            throw RemoteAudioException(
                                RemoteAudioFailureKind.TOO_LARGE,
                                "The remote audio exceeded AudioLens's configured source-size limit while downloading.",
                                "The SFTP stream exceeded the configured maximum source size.",
                            )
                        }
                    }
                }
            }
            RemoteDownloadResult(metadata, true)
        } finally {
            remoteFile.close()
        }
    }

    private fun <T> withConnection(
        serverId: String,
        indicator: ProgressIndicator,
        operation: (RemoteConnection) -> T,
    ): T {
        val server = manager().findServer(serverId, false)
            ?: throw RemoteAudioException(
                RemoteAudioFailureKind.CONFIGURATION,
                "The selected SFTP configuration no longer exists. Choose it again in AudioLens settings.",
                "The stored Web Deployment server ID was not found.",
            )
        if (server.accessType != AccessType.SFTP) {
            throw RemoteAudioException(
                RemoteAudioFailureKind.CONFIGURATION,
                "The selected Deployment configuration is not SFTP.",
                "The stored Web Deployment server was not an SFTP server.",
            )
        }
        val deployable = Deployable.create(server, project)
            ?: throw RemoteAudioException(
                RemoteAudioFailureKind.CONFIGURATION,
                "PyCharm could not load the selected SFTP credentials.",
                "Web Deployment could not create an SFTP deployable.",
            )
        indicator.text = "Connecting to SFTP"
        val disconnectEvents = EventDispatcher.create(ForceDisconnectListener::class.java)
        val connection = try {
            RemoteConnectionManager.getInstance().openConnection(
                ConnectionOwnerFactory.createConnectionOwner(project),
                "AudioLens SFTP",
                deployable,
                FileTransferConfig.Origin.Default,
                disconnectEvents,
                indicator,
            )
        } catch (error: Throwable) {
            throw RemoteAudioException(
                RemoteAudioFailureKind.CONNECTION,
                "AudioLens could not connect with the selected PyCharm SFTP configuration. Test it in Deployment settings.",
                "SFTP connection failed (${error.javaClass.simpleName}).",
                error,
            )
        }
        try {
            return operation(connection)
        } catch (error: RemoteAudioException) {
            throw error
        } catch (error: Throwable) {
            throw RemoteAudioException(
                RemoteAudioFailureKind.DOWNLOAD,
                "AudioLens could not read the remote audio file. Check its path and SFTP permissions.",
                "SFTP file access failed (${error.javaClass.simpleName}).",
                error,
            )
        } finally {
            connection.release()
        }
    }

    private fun manager(): GroupedServersConfigManager = GroupedServersConfigManager.getInstance(project)

    private companion object {
        const val BUFFER_SIZE = 256 * 1024
        const val MEBIBYTE = 1024L * 1024L
    }
}
