package audiolens.pycharm.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioLensSettingsTest {
    @Test
    fun `new settings default to adaptive amplitude scale`() {
        assertEquals("adaptive", AudioLensSettings.SettingsState().amplitudeScaleMode)
    }
}
