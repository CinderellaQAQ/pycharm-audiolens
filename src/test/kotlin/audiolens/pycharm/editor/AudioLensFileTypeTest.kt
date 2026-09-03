package audiolens.pycharm.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioLensFileTypeTest {
    @Test
    fun `audio file type is binary and defaults to wav`() {
        assertEquals(AudioLensFileType.TYPE_NAME, AudioLensFileType.INSTANCE.name)
        assertEquals("wav", AudioLensFileType.INSTANCE.defaultExtension)
        assertTrue(AudioLensFileType.INSTANCE.isBinary)
    }

    @Test
    fun `plugin descriptor registers every supported extension`() {
        val descriptor = checkNotNull(javaClass.getResource("/META-INF/plugin.xml")).readText()
        val fileTypeTag = checkNotNull(
            Regex("""<fileType\b[^>]*implementationClass="audiolens\.pycharm\.editor\.AudioLensFileType"[^>]*/>""", RegexOption.DOT_MATCHES_ALL)
                .find(descriptor),
        ).value
        val declaredExtensions = checkNotNull(Regex("""extensions="([^"]+)"""").find(fileTypeTag))
            .groupValues[1]
            .split(';')
            .toSet()

        assertEquals(AudioLensFileType.SUPPORTED_EXTENSIONS, declaredExtensions)
    }
}
