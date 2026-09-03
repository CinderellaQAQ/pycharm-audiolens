package audiolens.pycharm.remote

enum class RemoteAudioFailureKind {
    CONFIGURATION,
    CONNECTION,
    NOT_FOUND,
    PERMISSION,
    TOO_LARGE,
    DOWNLOAD,
    UNSUPPORTED,
}

class RemoteAudioException(
    val kind: RemoteAudioFailureKind,
    val userMessage: String,
    val technicalDetail: String,
    cause: Throwable? = null,
) : RuntimeException(userMessage, cause)
