package audiolens.pycharm.remote

import audiolens.pycharm.settings.AudioLensSettings
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Properties
import kotlin.io.path.extension

data class RemoteCacheStats(val fileCount: Int, val bytes: Long)

@Service(Service.Level.PROJECT)
class RemoteAudioCacheService(@Suppress("unused") private val project: Project) {
    /**
     * Web Deployment has already downloaded a remote file before asking an editor to open it.
     * Persist that VirtualFile content into AudioLens' bounded cache so the regular range-based
     * audio pipeline can use it without treating a remote VFS path as a local NIO path.
     */
    fun importVirtualFile(
        file: VirtualFile,
        maximumBytes: Long,
        indicator: ProgressIndicator,
    ): Path = synchronized(CACHE_LOCK) {
        if (file.length > maximumBytes) {
            throw RemoteAudioException(
                RemoteAudioFailureKind.TOO_LARGE,
                "The remote audio exceeds the configured maximum source size.",
                "The Web Deployment virtual file exceeded the configured maximum source size.",
            )
        }
        val target = cacheFile("web-deployment", file.url)
        val part = target.resolveSibling("${target.fileName}.part")
        Files.createDirectories(target.parent)
        Files.deleteIfExists(part)
        try {
            file.inputStream.use { input ->
                Files.newOutputStream(
                    part,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        indicator.checkCanceled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > maximumBytes) {
                            throw RemoteAudioException(
                                RemoteAudioFailureKind.TOO_LARGE,
                                "The remote audio exceeds the configured maximum source size.",
                                "The Web Deployment virtual file stream exceeded the configured maximum source size.",
                            )
                        }
                        output.write(buffer, 0, count)
                        indicator.fraction = if (file.length > 0L) {
                            (copied.toDouble() / file.length.toDouble()).coerceIn(0.0, 1.0)
                        } else {
                            0.0
                        }
                    }
                }
            }
            moveReplacing(part, target)
            Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()))
            prune(AudioLensSettings.getInstance().state.remoteCacheMaxMB.toLong() * MEBIBYTE, target)
            target
        } finally {
            Files.deleteIfExists(part)
        }
    }

    fun fetch(
        provider: SftpRemoteAudioProvider,
        serverId: String,
        remotePath: String,
        maximumBytes: Long,
        indicator: ProgressIndicator,
    ): Path = synchronized(CACHE_LOCK) {
        val target = cacheFile(serverId, remotePath)
        Files.createDirectories(target.parent)
        val cached = if (Files.isRegularFile(target)) readMetadata(metadataFile(target)) else null
        val part = target.resolveSibling("${target.fileName}.part")
        Files.deleteIfExists(part)
        try {
            val result = provider.downloadIfChanged(serverId, remotePath, cached, part, maximumBytes, indicator)
            if (result.downloaded) {
                require(Files.isRegularFile(part)) { "The SFTP provider did not create the downloaded file." }
                if (result.metadata.size >= 0L && Files.size(part) != result.metadata.size) {
                    throw RemoteAudioException(
                        RemoteAudioFailureKind.DOWNLOAD,
                        "The remote audio download was incomplete. Please try again.",
                        "Downloaded byte count did not match the SFTP file size.",
                    )
                }
                moveReplacing(part, target)
                writeMetadata(metadataFile(target), result.metadata)
            } else if (!Files.isRegularFile(target)) {
                throw RemoteAudioException(
                    RemoteAudioFailureKind.DOWNLOAD,
                    "The remote audio cache is missing. Please try the download again.",
                    "SFTP returned an unchanged result without a cache file.",
                )
            }
            Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()))
            prune(AudioLensSettings.getInstance().state.remoteCacheMaxMB.toLong() * MEBIBYTE, target)
            return target
        } finally {
            Files.deleteIfExists(part)
        }
    }

    fun clear(): RemoteCacheStats = synchronized(CACHE_LOCK) {
        val before = stats()
        if (Files.exists(cacheRoot)) {
            Files.walk(cacheRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        return before
    }

    fun stats(): RemoteCacheStats = synchronized(CACHE_LOCK) {
        if (!Files.isDirectory(cacheRoot)) return RemoteCacheStats(0, 0)
        var count = 0
        var bytes = 0L
        Files.walk(cacheRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(METADATA_SUFFIX) }
                .forEach {
                    count++
                    bytes += runCatching { Files.size(it) }.getOrDefault(0L)
                }
        }
        return RemoteCacheStats(count, bytes)
    }

    private fun cacheFile(serverId: String, remotePath: String): Path {
        val key = sha256("$serverId\u0000$remotePath")
        val extension = Path.of(remotePath.substringAfterLast('/')).extension.lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        val originalName = remotePath.substringAfterLast('/').ifBlank { "audio" }
        val safeName = originalName
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .takeLast(160)
            .ifBlank { if (extension == null) "audio" else "audio.$extension" }
        return cacheRoot.resolve(key.take(2)).resolve(key).resolve(safeName)
    }

    private fun prune(maximumBytes: Long, protectedFile: Path) {
        if (maximumBytes <= 0 || !Files.isDirectory(cacheRoot)) return
        val files = Files.walk(cacheRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(METADATA_SUFFIX) }
                .map { CachedFile(it, Files.size(it), Files.getLastModifiedTime(it).toMillis()) }
                .toList()
        }
        var total = files.sumOf { it.size }
        files.sortedBy { it.lastAccess }.forEach { cached ->
            if (total <= maximumBytes) return
            if (cached.path == protectedFile) return@forEach
            if (Files.deleteIfExists(cached.path)) total -= cached.size
            Files.deleteIfExists(metadataFile(cached.path))
        }
    }

    private fun readMetadata(path: Path): RemoteFileMetadata? = runCatching {
        val properties = Properties()
        Files.newInputStream(path).use(properties::load)
        RemoteFileMetadata(
            properties.getProperty("size").toLong(),
            properties.getProperty("lastModified").toLong(),
        )
    }.getOrNull()

    private fun writeMetadata(path: Path, metadata: RemoteFileMetadata) {
        val properties = Properties().apply {
            setProperty("size", metadata.size.toString())
            setProperty("lastModified", metadata.lastModified.toString())
        }
        Files.newOutputStream(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { output -> properties.storeWithoutComments(output) }
    }

    private fun Properties.storeWithoutComments(output: OutputStream) {
        entries.sortedBy { it.key.toString() }.forEach { (key, value) ->
            output.write("$key=$value\n".toByteArray(Charsets.UTF_8))
        }
    }

    private fun moveReplacing(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private data class CachedFile(val path: Path, val size: Long, val lastAccess: Long)

    companion object {
        private const val COPY_BUFFER_SIZE = 256 * 1024
        private const val MEBIBYTE = 1024L * 1024L
        private const val METADATA_SUFFIX = ".metadata"
        private val CACHE_LOCK = Any()
        internal fun cacheRootPath(systemPath: String = PathManager.getSystemPath()): Path =
            Path.of(systemPath, "audiolens", "remote-cache")

        private val cacheRoot: Path
            get() = cacheRootPath()

        private fun metadataFile(audioFile: Path): Path = audioFile.resolveSibling("${audioFile.fileName}$METADATA_SUFFIX")

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
