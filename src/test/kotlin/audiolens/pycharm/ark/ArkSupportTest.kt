package audiolens.pycharm.ark

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ArkSupportTest {
    @Test
    fun `reads a RIFF WAVE entry at the requested offset`() {
        val file = Files.createTempFile("audiolens-", ".ark")
        try {
            val prefix = "utterance ".toByteArray()
            file.writeBytes(prefix + waveBytes(44) + byteArrayOf(1, 2, 3))
            val entry = ArkSupport.parseEntry(file, prefix.size.toLong())
            assertEquals(prefix.size.toLong(), entry.offset)
            assertEquals(44, entry.size)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `rejects offsets that do not point at RIFF WAVE`() {
        val file = Files.createTempFile("audiolens-", ".ark")
        try {
            file.writeBytes(ByteArray(32))
            assertFailsWith<IllegalArgumentException> { ArkSupport.parseEntry(file, 0) }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `rejects an entry that extends beyond the ark`() {
        val file = Files.createTempFile("audiolens-", ".ark")
        try {
            file.writeBytes(waveBytes(128).copyOf(44))
            assertFailsWith<IllegalArgumentException> { ArkSupport.parseEntry(file, 0) }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `parses paths with optional offsets including Windows drive letters`() {
        assertEquals(PathPair("/data/wav.ark", 23252), ArkSupport.parsePathAndOffset("/data/wav.ark:23252")?.let { PathPair(it.first.toString(), it.second) })
        assertEquals(PathPair("C:\\data\\wav.ark", 7), ArkSupport.parsePathAndOffset("C:\\data\\wav.ark:7")?.let { PathPair(it.first.toString(), it.second) })
        assertNull(ArkSupport.parsePathAndOffset("/data/not-audio.txt:7"))
    }

    private data class PathPair(val path: String, val offset: Long?)

    private fun waveBytes(totalSize: Int): ByteArray {
        require(totalSize >= 12)
        return ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(totalSize - 8)
            put("WAVE".toByteArray())
        }.array()
    }
}
