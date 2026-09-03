package audiolens.pycharm.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AudioPathReferenceParserTest {
    @Test
    fun `finds paths in Python JSON text logs and wav scp`() {
        assertPath("audio = 'samples/hello world.wav'", "hello", "samples/hello world.wav")
        assertPath("{\"audio\": \"assets/example.flac\"}", "example", "assets/example.flac")
        assertPath("input=recordings/test.opus, status=ready", "test", "recordings/test.opus")
        assertPath("utterance-001 data/train/sample.wav", "sample", "data/train/sample.wav")
    }

    @Test
    fun `finds absolute paths and source escaped Windows paths`() {
        assertPath("/datasets/speech/a.wav", "speech", "/datasets/speech/a.wav")
        assertPath("audio = \"C:\\\\datasets\\\\speech\\\\a.wav\"", "speech", "C:\\\\datasets\\\\speech\\\\a.wav")
    }

    @Test
    fun `finds Kaldi ark offset without treating the offset as part of the file name`() {
        val line = "utt-1 data/audio.ark:23252"
        val reference = assertNotNull(AudioPathReferenceParser.findAt(line, line.indexOf("audio")))
        assertEquals("data/audio.ark", reference.pathText)
        assertEquals(23252, reference.arkOffset)
        assertEquals("data/audio.ark:23252", line.substring(reference.startOffset, reference.endOffset))
    }

    @Test
    fun `ignores unsupported files and ordinary source text`() {
        assertNull(AudioPathReferenceParser.findAt("config/settings.json", 8))
        assertNull(AudioPathReferenceParser.findAt("print('hello')", 9))
    }

    private fun assertPath(line: String, clickedText: String, expected: String) {
        val reference = assertNotNull(AudioPathReferenceParser.findAt(line, line.indexOf(clickedText)))
        assertEquals(expected, reference.pathText)
    }
}
