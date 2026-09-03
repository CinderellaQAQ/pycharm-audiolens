package audiolens.pycharm.editor

import audiolens.pycharm.remote.RemoteAudioCacheService
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.common.ThreadLeakTracker
import org.junit.Assert.assertSame
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files

class AudioLensFileTypeRegistrationTest : BasePlatformTestCase() {
    private val rootAccessDisposable = Disposer.newDisposable("AudioLens file type registration test")

    @BeforeTest
    override fun setUp() {
        VfsRootAccess.allowRootAccess(rootAccessDisposable, "/usr/bin")
        super.setUp()
        ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(), "SystemPropertyWatcher")
    }

    @AfterTest
    override fun tearDown() {
        try {
            super.tearDown()
        } finally {
            Disposer.dispose(rootAccessDisposable)
        }
    }

    @Test
    fun testSupportedExtensionsAreRegisteredWithTheIde() {
        val fileTypes = FileTypeManager.getInstance()

        for (extension in AudioLensFileType.SUPPORTED_EXTENSIONS) {
            assertSame(
                "*.$extension should be handled by AudioLens without prompting for a file association",
                AudioLensFileType.INSTANCE,
                fileTypes.getFileTypeByExtension(extension),
            )
        }
    }

    @Test
    fun testEditorProviderAcceptsWebDeploymentVirtualAudio() {
        val remoteFile = LightVirtualFile("remote.wav", AudioLensFileType.INSTANCE, "remote audio content")

        assertFalse(remoteFile.isInLocalFileSystem)
        assertTrue(AudioLensFileEditorProvider().accept(project, remoteFile))
    }

    @Test
    fun testWebDeploymentVirtualAudioCanBeImportedIntoLocalCache() {
        val content = "RIFF remote audio content"
        val remoteFile = LightVirtualFile("remote.wav", AudioLensFileType.INSTANCE, content)
        val cache = project.service<RemoteAudioCacheService>()

        try {
            val cachedFile = cache.importVirtualFile(remoteFile, 1024, EmptyProgressIndicator())

            assertTrue(Files.isRegularFile(cachedFile))
            assertContentEquals(content.toByteArray(), Files.readAllBytes(cachedFile))
        } finally {
            cache.clear()
        }
    }
}
