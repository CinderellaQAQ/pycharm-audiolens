package audiolens.pycharm.audio

import audiolens.pycharm.settings.AudioLensSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.TimeUnit

object FfmpegService {
    private val timeout: Duration = Duration.ofMinutes(5)

    fun transcodeToPcm16Wave(input: Path, output: Path, maxBytes: Long) {
        run(
            FfmpegOperation.DECODE,
            listOf(
                "-hide_banner", "-loglevel", "error", "-y", "-i", input.toString(),
                "-vn", "-f", "wav", "-acodec", "pcm_s16le", "-fs", maxBytes.toString(), output.toString(),
            ),
            output.parent.resolve("ffmpeg-stderr.txt"),
        )
        if (!Files.isRegularFile(output)) throw FfmpegFailures.outputMissing(FfmpegOperation.DECODE)
        if (Files.size(output) >= maxBytes) {
            throw FfmpegFailures.cacheLimit(FfmpegOperation.DECODE, formatBytes(maxBytes))
        }
    }

    fun exportSelection(inputWave: Path, output: Path, startTime: Double, duration: Double) {
        val temporary = output.resolveSibling(".${output.fileName}.audiolens.tmp.wav")
        try {
            run(
                FfmpegOperation.EXPORT,
                listOf(
                    "-hide_banner", "-loglevel", "error", "-y", "-ss", startTime.toString(),
                    "-t", duration.toString(), "-i", inputWave.toString(), "-vn", "-c:a", "pcm_s16le", temporary.toString(),
                ),
                temporary.resolveSibling(".audiolens-selection-stderr.txt"),
            )
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun run(operation: FfmpegOperation, arguments: List<String>, stderrPath: Path) {
        val configured = AudioLensSettings.getInstance().state.ffmpegPath
        val executable = FfmpegResolver.resolve(configured)
            ?: throw FfmpegFailures.notFound(operation, configured.isNotBlank())
        Files.createDirectories(stderrPath.parent)
        val process = try {
            ProcessBuilder(listOf(executable) + arguments)
                .redirectError(stderrPath.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (error: Exception) {
            throw FfmpegFailures.cannotStart(operation, error)
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                throw FfmpegFailures.timedOut(operation, timeout.toMinutes())
            }
            if (process.exitValue() != 0) {
                val detail = runCatching { Files.readString(stderrPath).takeLast(8192).trim() }.getOrDefault("")
                throw FfmpegFailures.processFailed(operation, process.exitValue(), redactPaths(detail, arguments))
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw FfmpegFailures.cannotStart(operation, error)
        } finally {
            runCatching { Files.deleteIfExists(stderrPath) }
        }
    }

    internal fun redactPaths(detail: String, arguments: List<String>): String {
        var safe = detail
        arguments.forEachIndexed { index, argument ->
            if (index == arguments.lastIndex || arguments.getOrNull(index - 1) == "-i") {
                safe = safe.replace(argument, if (index == arguments.lastIndex) "<temporary-output>" else "<audio-input>")
            }
        }
        return safe
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
