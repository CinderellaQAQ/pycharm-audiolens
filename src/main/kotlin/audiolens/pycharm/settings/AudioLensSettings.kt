package audiolens.pycharm.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "AudioLensSettings", storages = [Storage("audiolens.xml")])
class AudioLensSettings : PersistentStateComponent<AudioLensSettings.SettingsState> {
    data class SettingsState(
        var autoAnalyze: Boolean = true,
        var language: String = "auto",
        var windowFunction: String = "hamming",
        var fftSize: Int = 512,
        var zeroPaddingFactor: Int = 2,
        var amplitudeScaleMode: String = "adaptive",
        var maxFileSizeMB: Int = 512,
        var profileSpectrogram: Boolean = false,
        var ffmpegPath: String = "",
        var remoteSftpEnabled: Boolean = false,
        var remoteSftpServerId: String = "",
        var remotePathPrefix: String = "",
        var remoteBasePath: String = "",
        var remoteCacheMaxMB: Int = 2048,
        var preferencesJson: String = "{}",
    )

    private var value = SettingsState()

    override fun getState(): SettingsState = value

    override fun loadState(state: SettingsState) {
        value = state
    }

    companion object {
        fun getInstance(): AudioLensSettings =
            ApplicationManager.getApplication().getService(AudioLensSettings::class.java)
    }
}
