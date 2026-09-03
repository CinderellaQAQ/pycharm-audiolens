package audiolens.pycharm.web

import audiolens.pycharm.audio.FfmpegService
import audiolens.pycharm.compat.ProjectTrust
import audiolens.pycharm.audio.AudioCacheCoordinator
import audiolens.pycharm.audio.StreamedAudioCache
import audiolens.pycharm.audio.AudioLensFfmpegException
import audiolens.pycharm.diagnostics.AudioLensDiagnostics
import audiolens.pycharm.settings.AudioLensSettings
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.UIManager

class AudioLensSession(
    val project: Project,
    val source: AudioSource,
    private val bridgeExpression: String,
    private val dispatchJavascript: (String) -> Unit,
) : AutoCloseable {
    val payloads = PayloadStore()
    lateinit var token: String

    private val gson = Gson()
    private val registry = ApplicationManager.getApplication().getService(AudioLensSessionRegistry::class.java)
    private val executor = AppExecutorUtil.getAppExecutorService()
    private val closed = AtomicBoolean()
    private val selectionWrites = ConcurrentHashMap<Int, PendingSelection>()
    private val log = Logger.getInstance(AudioLensSession::class.java)
    @Volatile private var streamedCache: CompletableFuture<StreamedAudioCache>? = null

    fun handle(rawMessage: String) {
        if (closed.get()) return
        try {
            val message = gson.fromJson(rawMessage, JsonObject::class.java)
            when (message.string("type")) {
                "ready" -> dispatch(bootstrapMessage())
                "readChunk" -> readChunk(message)
                "prepareStreamedAudio" -> withCache(message.int("requestId")) { cache ->
                    json("streamedAudioReady", message.int("requestId")).apply {
                        add("metadata", JsonObject().apply {
                            addProperty("sampleRate", cache.sampleRate)
                            addProperty("numberOfChannels", cache.numberOfChannels)
                            addProperty("length", cache.length)
                            addProperty("duration", cache.duration)
                            add("channelPeaks", gson.toJsonTree(cache.channelPeaks))
                            add("channelRms", gson.toJsonTree(cache.channelRms))
                        })
                    }
                }
                "readStreamedAudioPeaks" -> withCache(message.int("requestId")) { cache ->
                    val peaks = cache.readWaveformPeaks(
                        message.int("channel"), message.long("startSample"), message.long("endSample"), message.int("width"),
                    )
                    json("streamedAudioPeaks", message.int("requestId")).apply {
                        addProperty("minUrl", payloadUrl(payloads.put(floatBytes(peaks.first))))
                        addProperty("maxUrl", payloadUrl(payloads.put(floatBytes(peaks.second))))
                    }
                }
                "readStreamedAudioSamples" -> withCache(message.int("requestId")) { cache ->
                    val samples = cache.readChannelSamples(
                        message.int("channel"), message.long("startSample"), message.long("endSample"), MAX_STREAMED_RESPONSE_BYTES,
                    )
                    json("streamedAudioSamples", message.int("requestId")).apply {
                        addProperty("samplesUrl", payloadUrl(payloads.put(floatBytes(samples))))
                    }
                }
                "readStreamedAudioWindows" -> withCache(message.int("requestId")) { cache ->
                    val windows = cache.readPackedWindows(
                        message.int("channel"), message.long("startSample"), message.long("endSample"),
                        message.int("windowSize"), message.int("hopSize"), message.int("maxFrames"), MAX_STREAMED_RESPONSE_BYTES,
                    )
                    json("streamedAudioWindows", message.int("requestId")).apply {
                        addProperty("samplesUrl", payloadUrl(payloads.put(floatBytes(windows.samples))))
                        addProperty("frameCount", windows.frameCount)
                        addProperty("windowSize", windows.windowSize)
                    }
                }
                "downloadAudio" -> chooseDestination(source.displayName, listOf(source.extension.ifEmpty { "audio" })) { destination ->
                    executor.execute {
                        runCatching { copySource(destination) }
                            .onSuccess { notifyInfo("Saved ${destination.fileName}.") }
                            .onFailure { showError(it.message ?: "Cannot save audio.") }
                    }
                }
                "requestSelectionWavSave" -> requestSelectionSave(message)
                "writeSelectionWavChunk" -> writeSelectionChunk(message)
                "saveStreamedSelectionWav" -> saveStreamedSelection(message)
                "updatePreferences" -> updatePreferences(message)
                "showError" -> showError(message.string("message"))
                else -> log.warn("Ignoring unknown AudioLens web message: ${message.string("type")}")
            }
        } catch (error: Throwable) {
            log.warn("AudioLens host message failed", error)
            dispatch(JsonObject().apply {
                addProperty("type", "error")
                addProperty("message", error.message ?: error.javaClass.simpleName)
            })
        }
    }

    fun html(): String {
        val theme = themeVariables()
        val language = AudioLensSettings.getInstance().state.language.let { if (it == "auto") "en" else it }
        return """<!doctype html>
<html lang="${escapeHtml(language)}">
<head>
  <meta charset="UTF-8">
  <meta http-equiv="Content-Security-Policy" content="default-src 'self'; img-src 'self' blob: data:; media-src 'self' blob: data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; worker-src blob:; connect-src 'self' blob: data:">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <style>:root{$theme}html,body,#app{height:100%;margin:0}</style>
  <title>AudioLens</title>
</head>
<body>
  <div id="app"></div>
  <script>window.__audioLensHostSend=function(message){$bridgeExpression};</script>
  <script src="webview.js"></script>
</body>
</html>"""
    }

    fun sourceChanged() {
        invalidateCache()
        dispatch(JsonObject().apply {
            addProperty("type", "fileChanged")
            add("metadata", metadata())
        })
    }

    fun settingsChanged() {
        dispatch(JsonObject().apply {
            addProperty("type", "configChanged")
            add("config", config())
        })
    }

    private fun bootstrapMessage(): JsonObject = JsonObject().apply {
        addProperty("type", "bootstrap")
        add("config", config())
        val preferences = runCatching {
            gson.fromJson(AudioLensSettings.getInstance().state.preferencesJson, JsonObject::class.java)
        }.getOrNull() ?: JsonObject()
        add("preferences", preferences)
        add("metadata", metadata())
    }

    private fun config(): JsonObject {
        val state = AudioLensSettings.getInstance().state
        return JsonObject().apply {
            addProperty("autoAnalyze", state.autoAnalyze)
            addProperty("maxFileSizeMB", state.maxFileSizeMB.coerceIn(16, 4096))
            addProperty("language", state.language)
            addProperty("vscodeLanguage", System.getProperty("user.language", "en"))
            addProperty("profileSpectrogram", state.profileSpectrogram)
            add("analysis", JsonObject().apply {
                addProperty("windowFunction", state.windowFunction)
                addProperty("fftSize", state.fftSize)
                addProperty("zeroPaddingFactor", state.zeroPaddingFactor)
                addProperty("amplitudeScaleMode", state.amplitudeScaleMode)
            })
        }
    }

    private fun metadata(): JsonObject = JsonObject().apply {
        addProperty("fileName", source.displayName)
        addProperty("uri", "audiolens:///${URLEncoder.encode(source.displayName, Charsets.UTF_8)}")
        addProperty("size", source.size)
        addProperty("trusted", ProjectTrust.isTrusted(project))
        addProperty("extension", source.extension)
        addProperty("kind", if (source.extension == "pcm" || source.extension == "raw") "pcm" else "encoded")
        source.sourceKind?.let { addProperty("sourceKind", it) }
        if (source.sourceKind == "ark") addProperty("sourceOffset", source.offset)
    }

    private fun readChunk(message: JsonObject) {
        val requestId = message.int("requestId")
        try {
            assertTransferAllowed()
            val offset = message.long("offset")
            val length = message.int("length")
            require(offset >= 0 && length in 1..AudioSource.MAX_CHUNK_SIZE && offset + length <= source.size) {
                "Invalid audio chunk request."
            }
            dispatch(json("chunk", requestId).apply {
                addProperty("offset", offset)
                addProperty("total", source.size)
                addProperty("bytesUrl", "${registry.url(token, "source")}?offset=$offset&length=$length&stamp=${url(source.stamp)}")
            })
        } catch (error: Throwable) {
            dispatch(json("chunkError", requestId).apply { addProperty("message", error.message ?: "Cannot read audio.") })
        }
    }

    private fun withCache(requestId: Int, operation: (StreamedAudioCache) -> JsonObject) {
        assertTransferAllowedOrReply(requestId) ?: return
        cacheFuture().thenApplyAsync(operation, executor).whenComplete { response, error ->
            if (error != null) streamedError(requestId, error.cause ?: error) else dispatch(response)
        }
    }

    @Synchronized
    private fun cacheFuture(): CompletableFuture<StreamedAudioCache> {
        streamedCache?.let { return it }
        val future = CompletableFuture.supplyAsync({
            var input = source.path
            var copied: Path? = null
            try {
                if (source.offset != 0L || source.size != Files.size(source.path)) {
                    copied = Files.createTempFile("audiolens-ark-entry-", ".wav")
                    copySource(copied)
                    input = copied
                }
                val maxTransfer = maxTransferBytes()
                val maxCache = minOf(4L * 1024 * 1024 * 1024 - 2L * 1024 * 1024, maxOf(1024L * 1024 * 1024, maxTransfer * 8))
                project.service<AudioCacheCoordinator>().withFfmpegSlot {
                    StreamedAudioCache.create(input, maxCache)
                }
            } finally {
                copied?.let { Files.deleteIfExists(it) }
            }
        }, executor)
        future.whenComplete { _, error -> if (error != null) synchronized(this) { if (streamedCache === future) streamedCache = null } }
        streamedCache = future
        return future
    }

    private fun requestSelectionSave(message: JsonObject) {
        val requestId = message.int("requestId")
        chooseDestination(safeName(message.string("fileName"), "audiolens_selection.wav"), listOf("wav"), onCancel = {
            dispatch(json("selectionWavSaveCanceled", requestId))
        }) { destination ->
            selectionWrites[requestId] = PendingSelection(destination)
            dispatch(json("selectionWavSaveReady", requestId))
        }
    }

    private fun writeSelectionChunk(message: JsonObject) {
        val requestId = message.int("requestId")
        executor.execute {
            try {
                val pending = selectionWrites[requestId] ?: return@execute
                val index = message.int("chunkIndex")
                val bytes = Base64.getDecoder().decode(message.string("bytesBase64"))
                synchronized(pending) {
                    require(index == pending.nextChunk) { "Selection WAV chunks arrived out of order." }
                    require(pending.output.size().toLong() + bytes.size <= maxTransferBytes()) { "Selection WAV is too large." }
                    pending.output.write(bytes)
                    pending.nextChunk++
                    if (message.boolean("isLast")) {
                        Files.write(pending.destination, pending.output.toByteArray())
                        selectionWrites.remove(requestId)
                        notifyInfo("Saved ${pending.destination.fileName}.")
                    }
                }
            } catch (error: Throwable) {
                selectionWrites.remove(requestId)
                showError(error.message ?: "Cannot save the selected audio.")
            }
        }
    }

    private fun saveStreamedSelection(message: JsonObject) {
        val requestId = message.int("requestId")
        val name = safeName(message.string("fileName"), "audiolens_selection.wav")
        chooseDestination(name, listOf("wav")) { destination ->
            assertTransferAllowedOrReply(requestId) ?: return@chooseDestination
            cacheFuture().thenAcceptAsync({ cache ->
                val start = message.double("startTime").coerceIn(0.0, cache.duration)
                val end = message.double("endTime").coerceIn(start, cache.duration)
                require(end > start) { "The selected audio range is empty." }
                project.service<AudioCacheCoordinator>().withFfmpegSlot {
                    FfmpegService.exportSelection(cache.wavePath, destination, start, end - start)
                }
                notifyInfo("Saved ${destination.fileName}.")
            }, executor).exceptionally { error ->
                streamedError(requestId, error.cause ?: error)
                null
            }
        }
    }

    private fun updatePreferences(message: JsonObject) {
        val preferences = message.getAsJsonObject("preferences") ?: JsonObject()
        val text = gson.toJson(preferences)
        require(text.length <= 64 * 1024) { "AudioLens preferences are too large." }
        AudioLensSettings.getInstance().state.preferencesJson = text
    }

    private fun chooseDestination(
        fileName: String,
        extensions: List<String>,
        onCancel: () -> Unit = {},
        onChosen: (Path) -> Unit,
    ) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || closed.get()) return@invokeLater
            val descriptor = FileSaverDescriptor("Save Audio", "Choose where to save the audio file.", extensions.firstOrNull() ?: "")
            val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            val wrapper = dialog.save(project.basePath?.let(Path::of), fileName)
            if (wrapper == null) onCancel() else onChosen(wrapper.file.toPath())
        }
    }

    private fun copySource(destination: Path) {
        assertTransferAllowed()
        require(destination.toAbsolutePath().normalize() != source.path.toAbsolutePath().normalize()) {
            "Choose a destination different from the source audio file."
        }
        Files.createDirectories(destination.toAbsolutePath().parent)
        FileChannel.open(source.path, StandardOpenOption.READ).use { input ->
            FileChannel.open(destination, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { output ->
                var position = 0L
                while (position < source.size) {
                    val transferred = input.transferTo(source.offset + position, source.size - position, output)
                    if (transferred <= 0) error("Cannot copy the complete audio source.")
                    position += transferred
                }
            }
        }
    }

    private fun assertTransferAllowed() {
        require(ProjectTrust.isTrusted(project)) { "This project is not trusted; AudioLens will not read audio content." }
        val maximum = maxTransferBytes()
        require(source.size <= maximum) { "Audio file is too large: ${source.size} bytes / ${maximum / (1024 * 1024)} MB." }
    }

    private fun assertTransferAllowedOrReply(requestId: Int): Unit? = try {
        assertTransferAllowed()
        Unit
    } catch (error: Throwable) {
        streamedError(requestId, error)
        null
    }

    private fun maxTransferBytes(): Long = AudioLensSettings.getInstance().state.maxFileSizeMB.coerceIn(16, 4096) * 1024L * 1024L

    private fun payloadUrl(id: String): String = "${registry.url(token, "payload")}?id=$id"

    private fun streamedError(requestId: Int, error: Throwable) {
        val actual = unwrap(error)
        if (actual is AudioLensFfmpegException) {
            AudioLensDiagnostics.reportFfmpegFailure(project, source, actual)
        }
        dispatch(json("streamedAudioError", requestId).apply {
            addProperty("message", actual.message ?: actual.javaClass.simpleName)
        })
    }

    private tailrec fun unwrap(error: Throwable): Throwable {
        val cause = error.cause ?: return error
        return if (error is java.util.concurrent.CompletionException || error is java.util.concurrent.ExecutionException) unwrap(cause) else error
    }

    private fun dispatch(message: JsonObject) {
        if (!closed.get()) dispatchJavascript(gson.toJson(message))
    }

    private fun notifyInfo(message: String) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) Messages.showInfoMessage(project, message, "AudioLens")
        }
    }

    private fun showError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) Messages.showErrorDialog(project, message, "AudioLens")
        }
    }

    private fun invalidateCache() {
        val existing = synchronized(this) { streamedCache.also { streamedCache = null } }
        existing?.thenAccept { it.close() }
        payloads.clear()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        invalidateCache()
        selectionWrites.clear()
    }

    private data class PendingSelection(val destination: Path, val output: ByteArrayOutputStream = ByteArrayOutputStream(), var nextChunk: Int = 0)

    private fun JsonObject.string(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString ?: ""
    private fun JsonObject.int(name: String): Int = get(name)?.asInt ?: 0
    private fun JsonObject.long(name: String): Long = get(name)?.asLong ?: 0L
    private fun JsonObject.double(name: String): Double = get(name)?.asDouble ?: 0.0
    private fun JsonObject.boolean(name: String): Boolean = get(name)?.asBoolean ?: false

    companion object {
        private const val MAX_STREAMED_RESPONSE_BYTES = 32 * 1024 * 1024

        private fun json(type: String, requestId: Int? = null): JsonObject = JsonObject().apply {
            addProperty("type", type)
            requestId?.let { addProperty("requestId", it) }
        }

        private fun url(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

        private fun floatBytes(values: FloatArray): ByteArray =
            ByteBuffer.allocate(values.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
                asFloatBuffer().put(values)
            }.array()

        private fun safeName(value: String, fallback: String): String =
            value.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._ -]"), "_").take(180).ifBlank { fallback }

        private fun escapeHtml(value: String): String = value
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

        private fun themeVariables(): String {
            fun color(key: String, fallback: String): String {
                val value = UIManager.getColor(key) ?: return fallback
                return "#%02x%02x%02x".format(value.red, value.green, value.blue)
            }
            val background = color("EditorPane.background", "#1e1e1e")
            val foreground = color("EditorPane.foreground", "#d4d4d4")
            val panel = color("Panel.background", background)
            val border = color("Component.borderColor", "#3f4751")
            val muted = color("Label.disabledForeground", "#9aa7b4")
            val input = color("TextField.background", panel)
            val accent = color("Component.focusColor", "#3794ff")
            return """
                --vscode-font-family:${escapeHtml(UIManager.getFont("Label.font")?.family ?: "sans-serif")};
                --vscode-font-size:13px;--vscode-editor-font-family:monospace;
                --vscode-foreground:$foreground;--vscode-editor-background:$background;
                --vscode-sideBar-background:$panel;--vscode-panel-border:$border;
                --vscode-descriptionForeground:$muted;--vscode-focusBorder:$accent;
                --vscode-input-foreground:$foreground;--vscode-input-background:$input;--vscode-input-border:$border;
                --vscode-button-foreground:#ffffff;--vscode-button-background:$accent;
                --vscode-button-hoverBackground:$accent;--vscode-button-secondaryForeground:$foreground;
                --vscode-button-secondaryBackground:$panel;--vscode-charts-blue:$accent;
                --vscode-charts-orange:#d18616;--vscode-errorForeground:#f85149;
                --vscode-editorWarning-foreground:#cca700;--vscode-testing-iconPassed:#73c991;
                --vscode-notificationsInfoIcon-foreground:$accent;--vscode-notificationsWarningIcon-foreground:#cca700;
                --vscode-notificationsErrorIcon-foreground:#f85149;--vscode-menu-background:$panel;
                --vscode-menu-foreground:$foreground;--vscode-menu-border:$border;
                --vscode-list-activeSelectionBackground:$accent;--vscode-list-activeSelectionForeground:#ffffff;
            """.replace("\n", "").trim()
        }
    }
}
