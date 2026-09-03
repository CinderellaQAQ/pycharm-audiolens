package audiolens.pycharm.audio

enum class FfmpegOperation(val description: String) {
    DECODE("decode this audio file"),
    EXPORT("export the selected audio"),
}

enum class FfmpegFailureKind {
    NOT_FOUND,
    CANNOT_START,
    TIMED_OUT,
    UNSUPPORTED_OR_DAMAGED_INPUT,
    PERMISSION_DENIED,
    PROCESS_FAILED,
    OUTPUT_MISSING,
    CACHE_LIMIT,
}

class AudioLensFfmpegException(
    val kind: FfmpegFailureKind,
    val operation: FfmpegOperation,
    val userMessage: String,
    val technicalDetail: String,
    cause: Throwable? = null,
) : IllegalStateException(userMessage, cause)

internal object FfmpegFailures {
    fun notFound(operation: FfmpegOperation, configured: Boolean): AudioLensFfmpegException {
        val detail = if (configured) {
            "The configured FFmpeg path does not point to an executable file."
        } else {
            "FFmpeg was not found in PATH or common installation locations."
        }
        return AudioLensFfmpegException(
            FfmpegFailureKind.NOT_FOUND,
            operation,
            if (configured) {
                "AudioLens cannot use the configured FFmpeg executable. Open Settings | Tools | AudioLens, choose the FFmpeg program itself, then use Detect / Test FFmpeg."
            } else {
                "This audio needs FFmpeg, but AudioLens could not find it. Install FFmpeg or select its executable under Settings | Tools | AudioLens, then reopen the audio file."
            },
            detail,
        )
    }

    fun cannotStart(operation: FfmpegOperation, error: Throwable): AudioLensFfmpegException = AudioLensFfmpegException(
        FfmpegFailureKind.CANNOT_START,
        operation,
        "AudioLens found FFmpeg but could not start it. Check that the selected file is executable and permitted by the operating system.",
        "${error.javaClass.name}: ${error.message ?: "No detail"}",
        error,
    )

    fun timedOut(operation: FfmpegOperation, minutes: Long): AudioLensFfmpegException = AudioLensFfmpegException(
        FfmpegFailureKind.TIMED_OUT,
        operation,
        "FFmpeg took longer than $minutes minutes to ${operation.description}. The file may be unusually large, damaged, or stored on a slow drive.",
        "FFmpeg process timed out after $minutes minutes.",
    )

    fun processFailed(operation: FfmpegOperation, exitCode: Int, stderr: String): AudioLensFfmpegException {
        val normalized = stderr.trim().ifEmpty { "FFmpeg produced no error output." }
        val lower = normalized.lowercase()
        val kind: FfmpegFailureKind
        val message: String
        when {
            "permission denied" in lower || "operation not permitted" in lower -> {
                kind = FfmpegFailureKind.PERMISSION_DENIED
                message = "FFmpeg could not read or write the audio data because access was denied. Check the file and destination permissions."
            }
            "invalid data found" in lower || "could not find codec parameters" in lower || "unknown format" in lower -> {
                kind = FfmpegFailureKind.UNSUPPORTED_OR_DAMAGED_INPUT
                message = "FFmpeg could not decode this audio file. It may be damaged, incomplete, or encoded with an unsupported codec."
            }
            else -> {
                kind = FfmpegFailureKind.PROCESS_FAILED
                message = "FFmpeg could not ${operation.description}. Use Copy AudioLens Diagnostics to include its technical error when reporting the problem."
            }
        }
        return AudioLensFfmpegException(kind, operation, message, "FFmpeg exit code: $exitCode\n$normalized")
    }

    fun outputMissing(operation: FfmpegOperation): AudioLensFfmpegException = AudioLensFfmpegException(
        FfmpegFailureKind.OUTPUT_MISSING,
        operation,
        "FFmpeg finished without producing usable audio. The source may be empty, damaged, or contain no audio stream.",
        "FFmpeg exited successfully but the expected output file was not created.",
    )

    fun cacheLimit(operation: FfmpegOperation, limit: String): AudioLensFfmpegException = AudioLensFfmpegException(
        FfmpegFailureKind.CACHE_LIMIT,
        operation,
        "The decoded audio would exceed AudioLens's $limit safety limit. Try a shorter file or a compressed preview.",
        "The FFmpeg PCM cache reached the configured safety limit ($limit).",
    )
}
