package audiolens.pycharm.web

import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AudioSourceTest {
    @Test
    fun `reads only the requested bounded range`() {
        val file = Files.createTempFile("audiolens-source-", ".wav")
        try {
            file.writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5))
            val source = AudioSource(file, "test.wav")
            assertContentEquals(byteArrayOf(2, 3, 4), source.read(2, 3, source.stamp))
            assertFailsWith<IllegalArgumentException> { source.read(5, 2, source.stamp) }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `regular source size follows file changes while ark slices stay fixed`() {
        val file = Files.createTempFile("audiolens-source-", ".wav")
        try {
            file.writeBytes(ByteArray(8))
            val regular = AudioSource(file, "test.wav")
            val slice = AudioSource(file, "entry.wav", 2, 4, "ark")
            file.writeBytes(ByteArray(12))
            assertEquals(12, regular.size)
            assertEquals(4, slice.size)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
