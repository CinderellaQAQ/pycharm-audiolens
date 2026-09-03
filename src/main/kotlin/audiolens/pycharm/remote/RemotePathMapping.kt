package audiolens.pycharm.remote

object RemotePathMapping {
    fun map(pathText: String, pathPrefix: String, remoteBasePath: String): String {
        val source = normalize(pathText)
        require(source.isNotBlank()) { "The remote audio path is empty." }
        require(!source.contains('\u0000')) { "The remote audio path contains an invalid character." }
        require(!SCHEME.containsMatchIn(source)) { "URLs are not supported. Use a path from the selected SFTP server." }

        val prefix = normalizePrefix(pathPrefix)
        val base = normalizeBase(remoteBasePath)
        val suffix = if (prefix.isNotEmpty()) {
            require(source == prefix || source.startsWith("$prefix/")) {
                "The audio path is outside the configured path prefix."
            }
            source.removePrefix(prefix).trimStart('/')
        } else {
            source
        }
        if (base.isNotEmpty() || prefix.isNotEmpty()) {
            require(suffix.split('/').none { it == ".." }) {
                "The remote audio path escapes the configured base directory."
            }
        }

        val mapped = when {
            base.isNotEmpty() && (prefix.isNotEmpty() || !suffix.startsWith('/')) -> join(base, suffix)
            else -> suffix
        }
        return normalizeSegments(mapped)
    }

    private fun normalize(value: String): String = value.trim().replace('\\', '/')

    private fun normalizePrefix(value: String): String = normalize(value).trimEnd('/').let {
        if (it == "/") "" else it
    }

    private fun normalizeBase(value: String): String = normalize(value).trimEnd('/').let {
        if (it == "/") "/" else it
    }

    private fun join(base: String, suffix: String): String = when {
        base == "/" -> "/${suffix.trimStart('/')}"
        suffix.isEmpty() -> base
        else -> "${base.trimEnd('/')}/${suffix.trimStart('/')}"
    }

    private fun normalizeSegments(value: String): String {
        val absolute = value.startsWith('/')
        val parts = ArrayDeque<String>()
        value.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    require(parts.isNotEmpty()) { "The remote audio path escapes the SFTP root." }
                    parts.removeLast()
                }
                else -> parts.addLast(segment)
            }
        }
        val normalized = parts.joinToString("/")
        return if (absolute) "/$normalized" else normalized
    }

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
}
