package audiolens.pycharm.web

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class AudioSource(
    val path: Path,
    val displayName: String,
    val offset: Long = 0,
    private val fixedSize: Long? = null,
    val sourceKind: String? = null,
) {
    val size: Long
        get() = fixedSize ?: (Files.size(path) - offset)
    val extension: String = displayName.substringAfterLast('.', "").lowercase()
    val stamp: String
        get() = "${Files.getLastModifiedTime(path).toMillis()}-${Files.size(path)}-$offset-$size"

    init {
        require(offset >= 0 && size >= 0 && offset + size >= offset && offset + size <= Files.size(path)) {
            "Audio source range is outside the file."
        }
    }

    fun read(relativeOffset: Long, length: Int, expectedStamp: String): ByteArray {
        require(expectedStamp == stamp) { "Audio file changed while it was being read." }
        require(relativeOffset >= 0 && length in 1..MAX_CHUNK_SIZE && relativeOffset + length <= size) {
            "Invalid audio byte range."
        }
        val buffer = ByteBuffer.allocate(length)
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            var position = offset + relativeOffset
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, position)
                if (read < 0) throw EOFException("Audio file ended while a chunk was being read.")
                position += read
            }
        }
        return buffer.array()
    }

    companion object {
        const val MAX_CHUNK_SIZE = 4 * 1024 * 1024
    }
}
