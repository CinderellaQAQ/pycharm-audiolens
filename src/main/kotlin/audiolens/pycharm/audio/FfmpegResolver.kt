package audiolens.pycharm.audio

import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class FfmpegTestResult(val path: String?, val message: String)

object FfmpegResolver {
    fun resolve(configuredPath: String?): String? {
        val explicit = configuredPath?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) {
            val path = Path.of(explicit).toAbsolutePath().normalize()
            return path.takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }?.toString()
        }

        val executableNames = if (SystemInfo.isWindows) listOf("ffmpeg.exe", "ffmpeg") else listOf("ffmpeg")
        val pathCandidates = System.getenv("PATH").orEmpty()
            .split(System.getProperty("path.separator"))
            .filter { it.isNotBlank() }
            .flatMap { directory -> executableNames.map { Path.of(directory, it) } }
        val common = when {
            SystemInfo.isMac -> listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/opt/local/bin/ffmpeg")
            SystemInfo.isWindows -> listOf(
                "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe",
                "C:\\ffmpeg\\bin\\ffmpeg.exe",
            )
            else -> listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/snap/bin/ffmpeg")
        }.map(Path::of)

        return (pathCandidates + common)
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
            ?.toAbsolutePath()?.normalize()?.toString()
    }

    fun test(configuredPath: String?): FfmpegTestResult {
        val executable = resolve(configuredPath)
            ?: return FfmpegTestResult(
                null,
                if (configuredPath.isNullOrBlank()) {
                    "FFmpeg was not found. Install it or choose the FFmpeg executable under Settings | Tools | AudioLens."
                } else {
                    "The configured path is not an executable FFmpeg program. Choose the ffmpeg or ffmpeg.exe file itself, not its containing folder."
                },
            )
        return try {
            val process = ProcessBuilder(executable, "-version").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText().take(8192) }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                FfmpegTestResult(null, "FFmpeg did not respond within 10 seconds. Check the executable and try running 'ffmpeg -version' in a terminal.")
            } else if (process.exitValue() != 0) {
                FfmpegTestResult(null, output.trim().ifEmpty { "FFmpeg exited with code ${process.exitValue()}." })
            } else {
                val version = output.lineSequence().firstOrNull().orEmpty()
                FfmpegTestResult(executable, "Found $version\n$executable")
            }
        } catch (error: Exception) {
            FfmpegTestResult(null, "AudioLens found FFmpeg but could not start it: ${error.message ?: error.javaClass.simpleName}")
        }
    }
}
