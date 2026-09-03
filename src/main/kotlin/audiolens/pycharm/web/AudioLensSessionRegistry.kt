package audiolens.pycharm.web

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.ide.BuiltInServerManager
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
class AudioLensSessionRegistry {
    private val sessions = ConcurrentHashMap<String, AudioLensSession>()
    private val log = Logger.getInstance(AudioLensSessionRegistry::class.java)
    val webviewBytes: ByteArray by lazy {
        checkNotNull(javaClass.getResourceAsStream("/web/webview.js")) { "Bundled AudioLens web UI is missing." }
            .use { it.readBytes() }
    }

    fun register(session: AudioLensSession): String {
        var token: String
        do token = PayloadStore.randomToken(32) while (sessions.putIfAbsent(token, session) != null)
        return token
    }

    fun unregister(token: String) {
        sessions.remove(token)?.payloads?.clear()
    }

    fun find(token: String): AudioLensSession? = sessions[token]

    fun settingsChanged() {
        sessions.values.forEach(AudioLensSession::settingsChanged)
    }

    fun url(token: String, resource: String): String {
        val port = BuiltInServerManager.getInstance().waitForStart().port
        if (port <= 0) log.warn("The IDE built-in HTTP server has not reported a valid port yet: $port")
        return "http://127.0.0.1:$port/audiolens/$token/$resource"
    }
}
