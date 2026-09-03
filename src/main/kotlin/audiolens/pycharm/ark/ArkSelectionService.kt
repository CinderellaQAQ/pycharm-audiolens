package audiolens.pycharm.ark

import com.intellij.openapi.components.Service
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class ArkSelectionService {
    private val offsets = ConcurrentHashMap<Path, Long>()

    fun remember(path: Path, offset: Long) {
        offsets[path.toAbsolutePath().normalize()] = offset
    }

    fun offsetFor(path: Path): Long? = offsets.remove(path.toAbsolutePath().normalize())
}
