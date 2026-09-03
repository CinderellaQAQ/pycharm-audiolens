package audiolens.pycharm.navigation

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioPathResolverTest {
    @Test
    fun `resolves relative paths from the current document before the project root`() {
        val project = Files.createTempDirectory("audiolens-project-")
        try {
            val document = project.resolve("manifests/train.json")
            document.parent.createDirectories()
            document.writeBytes(byteArrayOf())
            val besideDocument = document.parent.resolve("audio/example.wav")
            besideDocument.parent.createDirectories()
            besideDocument.writeBytes(byteArrayOf(1))
            val atProjectRoot = project.resolve("audio/example.wav")
            atProjectRoot.parent.createDirectories()
            atProjectRoot.writeBytes(byteArrayOf(2))

            val resolved = AudioPathResolver.resolve(
                AudioPathReference(0, 17, "audio/example.wav"),
                document,
                project,
            )
            assertEquals(besideDocument, resolved?.path)
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `falls back to the project root and preserves ark offsets`() {
        val project = Files.createTempDirectory("audiolens-project-")
        try {
            val document = project.resolve("manifests/wav.scp")
            document.parent.createDirectories()
            document.writeBytes(byteArrayOf())
            val ark = project.resolve("data/audio.ark")
            ark.parent.createDirectories()
            ark.writeBytes(byteArrayOf(1))

            val resolved = AudioPathResolver.resolve(
                AudioPathReference(0, 20, "data/audio.ark", 9876),
                document,
                project,
            )
            assertEquals(ark, resolved?.path)
            assertEquals(9876, resolved?.arkOffset)
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resolves absolute and source escaped paths and rejects missing files`() {
        val directory = Files.createTempDirectory("audiolens-path with spaces-")
        try {
            val audio = directory.resolve("sample.wav")
            audio.writeBytes(byteArrayOf(1))
            assertEquals(audio, AudioPathResolver.resolve(AudioPathReference(0, 1, audio.toString()), null, null)?.path)
            assertNull(AudioPathResolver.resolve(AudioPathReference(0, 1, "missing.wav"), null, directory))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
