package audiolens.pycharm

import kotlin.test.Test
import kotlin.test.assertContains

class AudioLensPluginDescriptorTest {
    @Test
    fun `plugin descriptor wires text navigation and diagnostics into PyCharm`() {
        val descriptor = checkNotNull(javaClass.getResource("/META-INF/plugin.xml")).readText()

        assertContains(descriptor, "audiolens.pycharm.navigation.AudioPathEditorFactoryListener")
        assertContains(descriptor, "audiolens.pycharm.diagnostics.AudioLensDiagnosticsService")
        assertContains(descriptor, "<notificationGroup id=\"AudioLens\"")
        assertContains(descriptor, "audiolens.pycharm.diagnostics.CopyAudioLensDiagnosticsAction")
        assertContains(descriptor, "audiolens.pycharm.remote.RemoteAudioCacheService")
        assertContains(descriptor, "audiolens.pycharm.remote.OpenRemoteAudioAction")
        assertContains(descriptor, "config-file=\"audiolens-sftp.xml\"")

        val sftpDescriptor = checkNotNull(javaClass.getResource("/META-INF/audiolens-sftp.xml")).readText()
        assertContains(sftpDescriptor, "audiolens.pycharm.remote.SftpRemoteAudioProvider")
        assertContains(sftpDescriptor, "audiolens.pycharm.remote.PyCharmDeploymentSftpProvider")
    }
}
