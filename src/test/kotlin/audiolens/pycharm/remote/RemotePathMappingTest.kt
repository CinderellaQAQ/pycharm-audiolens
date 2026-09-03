package audiolens.pycharm.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemotePathMappingTest {
    @Test
    fun `keeps absolute paths unchanged when no mapping is configured`() {
        assertEquals("/datasets/train/sample.wav", RemotePathMapping.map("/datasets/train/sample.wav", "", ""))
    }

    @Test
    fun `resolves relative paths below a remote base directory`() {
        assertEquals(
            "/mnt/datasets/train/sample.wav",
            RemotePathMapping.map("train/sample.wav", "", "/mnt/datasets"),
        )
    }

    @Test
    fun `replaces a path prefix with the configured remote base`() {
        assertEquals(
            "/mnt/datasets/train/sample.wav",
            RemotePathMapping.map("/data/train/sample.wav", "/data", "/mnt/datasets"),
        )
    }

    @Test
    fun `accepts Windows separators in text paths`() {
        assertEquals(
            "/mnt/datasets/train/sample.wav",
            RemotePathMapping.map("D:\\data\\train\\sample.wav", "D:/data", "/mnt/datasets"),
        )
    }

    @Test
    fun `rejects prefix mismatches and traversal outside a configured base`() {
        assertFailsWith<IllegalArgumentException> {
            RemotePathMapping.map("/data-other/sample.wav", "/data", "/mnt/datasets")
        }
        assertFailsWith<IllegalArgumentException> {
            RemotePathMapping.map("../secret.wav", "", "/mnt/datasets")
        }
    }

    @Test
    fun `rejects URLs because credentials and protocols belong to Deployment`() {
        assertFailsWith<IllegalArgumentException> {
            RemotePathMapping.map("sftp://example.test/audio.wav", "", "")
        }
    }
}
