package audiolens.pycharm.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FfmpegFailuresTest {
    @Test
    fun `missing FFmpeg explains whether detection or configuration failed`() {
        val automatic = FfmpegFailures.notFound(FfmpegOperation.DECODE, configured = false)
        val configured = FfmpegFailures.notFound(FfmpegOperation.DECODE, configured = true)

        assertEquals(FfmpegFailureKind.NOT_FOUND, automatic.kind)
        assertTrue(automatic.userMessage.contains("could not find it"))
        assertTrue(configured.userMessage.contains("configured FFmpeg executable"))
        assertTrue(configured.userMessage.contains("Detect / Test FFmpeg"))
    }

    @Test
    fun `FFmpeg stderr is converted to actionable categories`() {
        val damaged = FfmpegFailures.processFailed(FfmpegOperation.DECODE, 1, "Invalid data found when processing input")
        val denied = FfmpegFailures.processFailed(FfmpegOperation.EXPORT, 13, "Permission denied")
        val unknown = FfmpegFailures.processFailed(FfmpegOperation.DECODE, 2, "decoder exploded")

        assertEquals(FfmpegFailureKind.UNSUPPORTED_OR_DAMAGED_INPUT, damaged.kind)
        assertTrue(damaged.userMessage.contains("damaged"))
        assertEquals(FfmpegFailureKind.PERMISSION_DENIED, denied.kind)
        assertTrue(denied.userMessage.contains("permissions"))
        assertEquals(FfmpegFailureKind.PROCESS_FAILED, unknown.kind)
        assertTrue(unknown.userMessage.contains("Copy AudioLens Diagnostics"))
        assertTrue(unknown.technicalDetail.contains("exit code: 2"))
    }

    @Test
    fun `diagnostic stderr redacts source and temporary file paths`() {
        val source = "/private/dataset/speaker-001.wav"
        val output = "/tmp/audiolens-secret/decoded.wav"
        val arguments = listOf("-i", source, "-vn", output)
        val detail = "Error while opening $source; could not write $output"

        val redacted = FfmpegService.redactPaths(detail, arguments)
        assertFalse(redacted.contains(source))
        assertFalse(redacted.contains(output))
        assertTrue(redacted.contains("<audio-input>"))
        assertTrue(redacted.contains("<temporary-output>"))
    }
}
