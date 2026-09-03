package audiolens.pycharm.diagnostics

import audiolens.pycharm.audio.AudioLensFfmpegException
import audiolens.pycharm.audio.FfmpegTestResult
import audiolens.pycharm.remote.RemoteAudioException
import audiolens.pycharm.web.AudioSource
import com.intellij.openapi.components.Service
import java.time.Instant

data class FfmpegFailureSnapshot(
    val timestamp: Instant,
    val kind: String,
    val operation: String,
    val message: String,
    val technicalDetail: String,
    val sourceName: String,
    val sourceExtension: String,
    val sourceSize: Long,
    val sourceKind: String?,
)

data class FfmpegTestSnapshot(val timestamp: Instant, val path: String?, val message: String)

data class RemoteAudioSnapshot(
    val timestamp: Instant,
    val successful: Boolean,
    val kind: String,
    val sourceName: String,
    val sourceExtension: String,
    val sourceSize: Long?,
    val message: String,
    val technicalDetail: String,
)

@Service(Service.Level.APP)
class AudioLensDiagnosticsService {
    @Volatile
    private var lastFailure: FfmpegFailureSnapshot? = null

    @Volatile
    private var lastTest: FfmpegTestSnapshot? = null

    @Volatile
    private var lastRemoteOperation: RemoteAudioSnapshot? = null

    @Synchronized
    fun recordFailure(error: AudioLensFfmpegException, source: AudioSource): Boolean {
        val now = Instant.now()
        val previous = lastFailure
        val signature = "${error.kind}:${error.operation}:${error.technicalDetail}"
        val previousSignature = previous?.let { "${it.kind}:${it.operation}:${it.technicalDetail}" }
        lastFailure = FfmpegFailureSnapshot(
            now,
            error.kind.name,
            error.operation.name,
            error.userMessage,
            error.technicalDetail.take(12_000),
            source.displayName,
            source.extension,
            source.size,
            source.sourceKind,
        )
        return previousSignature != signature || previous.timestamp.isBefore(now.minusSeconds(NOTIFICATION_DEDUP_SECONDS))
    }

    fun recordTest(result: FfmpegTestResult) {
        lastTest = FfmpegTestSnapshot(Instant.now(), result.path, result.message.take(4000))
    }

    @Synchronized
    fun recordRemoteFailure(sourcePath: String, error: RemoteAudioException): Boolean {
        val now = Instant.now()
        val sourceName = sourcePath.substringAfterLast('/').substringAfterLast('\\').ifBlank { "unknown" }
        val previous = lastRemoteOperation
        val signature = "${error.kind}:${error.technicalDetail}"
        val previousSignature = previous?.let { "${it.kind}:${it.technicalDetail}" }
        lastRemoteOperation = RemoteAudioSnapshot(
            now,
            false,
            error.kind.name,
            sourceName,
            sourceName.substringAfterLast('.', "").lowercase(),
            null,
            error.userMessage.take(4000),
            error.technicalDetail.take(12_000),
        )
        return previousSignature != signature || previous.timestamp.isBefore(now.minusSeconds(NOTIFICATION_DEDUP_SECONDS))
    }

    fun recordRemoteSuccess(sourcePath: String, size: Long) {
        val sourceName = sourcePath.substringAfterLast('/').substringAfterLast('\\').ifBlank { "unknown" }
        lastRemoteOperation = RemoteAudioSnapshot(
            Instant.now(),
            true,
            "NONE",
            sourceName,
            sourceName.substringAfterLast('.', "").lowercase(),
            size,
            "Remote audio downloaded and opened.",
            "SFTP download completed successfully.",
        )
    }

    fun failure(): FfmpegFailureSnapshot? = lastFailure

    fun test(): FfmpegTestSnapshot? = lastTest

    fun remoteOperation(): RemoteAudioSnapshot? = lastRemoteOperation

    private companion object {
        const val NOTIFICATION_DEDUP_SECONDS = 30L
    }
}
