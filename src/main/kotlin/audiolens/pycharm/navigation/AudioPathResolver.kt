package audiolens.pycharm.navigation

import java.net.URI
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

data class ResolvedAudioPath(val path: Path, val arkOffset: Long?)

object AudioPathResolver {
    fun resolve(reference: AudioPathReference, documentPath: Path?, projectPath: Path?): ResolvedAudioPath? {
        val roots = buildList {
            documentPath?.toAbsolutePath()?.normalize()?.parent?.let(::add)
            projectPath?.toAbsolutePath()?.normalize()?.let { if (it !in this) add(it) }
        }
        for (candidateText in textCandidates(reference.pathText)) {
            val candidate = toPath(candidateText) ?: continue
            val paths = if (candidate.isAbsolute) listOf(candidate.normalize()) else roots.map { it.resolve(candidate).normalize() }
            for (path in paths) {
                if (Files.isRegularFile(path)) return ResolvedAudioPath(path, reference.arkOffset)
            }
        }
        return null
    }

    private fun textCandidates(value: String): List<String> {
        val trimmed = value.trim().removeSurrounding("\"").removeSurrounding("'")
        val expandedHome = if (trimmed == "~" || trimmed.startsWith("~/") || trimmed.startsWith("~\\")) {
            System.getProperty("user.home") + trimmed.drop(1)
        } else {
            trimmed
        }
        val unescaped = expandedHome.replace("\\\\", "\\").replace("\\/", "/")
        return listOf(expandedHome, unescaped).distinct()
    }

    private fun toPath(value: String): Path? = try {
        if (value.startsWith("file:", ignoreCase = true)) Path.of(URI(value)) else Path.of(value)
    } catch (_: InvalidPathException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
