package audiolens.pycharm.ark

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

data class ArkEntry(val path: Path, val offset: Long, val size: Long)

object ArkSupport {
    fun parseEntry(path: Path, offset: Long): ArkEntry {
        require(offset >= 0) { "Kaldi WAV ark offset must be a non-negative integer." }
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            if (offset > channel.size() - 12) {
                throw IllegalArgumentException("Kaldi WAV ark offset $offset is outside the file range.")
            }
            val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            readFully(channel, header, offset)
            val bytes = header.array()
            if (!bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) ||
                !bytes.copyOfRange(8, 12).contentEquals("WAVE".toByteArray(Charsets.US_ASCII))) {
                throw IllegalArgumentException("Kaldi WAV ark offset $offset does not point to RIFF/WAVE data.")
            }
            val riffSize = Integer.toUnsignedLong(header.getInt(4))
            val entrySize = riffSize + 8L
            if (entrySize < 12 || offset + entrySize < offset || offset + entrySize > channel.size()) {
                throw IllegalArgumentException("The WAV entry at offset $offset has an invalid size or exceeds the ark file.")
            }
            return ArkEntry(path, offset, entrySize)
        }
    }

    fun parsePathAndOffset(input: String): Pair<Path, Long?>? {
        val trimmed = input.trim().removeSurrounding("\"")
        if (trimmed.isEmpty()) return null
        val match = Regex("^(.*\\.ark)(?::(\\d+))?$", RegexOption.IGNORE_CASE).matchEntire(trimmed) ?: return null
        return Path.of(match.groupValues[1]) to match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toLongOrNull()
    }

    private fun readFully(channel: FileChannel, buffer: ByteBuffer, position: Long) {
        var cursor = position
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, cursor)
            if (count < 0) throw EOFException("Unexpected end of ark file.")
            cursor += count
        }
    }
}
