package audiolens.pycharm.audio

import com.intellij.openapi.components.Service
import java.util.concurrent.Semaphore

@Service(Service.Level.PROJECT)
class AudioCacheCoordinator {
    private val ffmpegSlot = Semaphore(1, true)

    fun <T> withFfmpegSlot(operation: () -> T): T {
        ffmpegSlot.acquire()
        try {
            return operation()
        } finally {
            ffmpegSlot.release()
        }
    }
}
