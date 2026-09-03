package audiolens.pycharm.editor

import com.intellij.openapi.fileTypes.FileType
import javax.swing.Icon

class AudioLensFileType private constructor() : FileType {
    override fun getName(): String = TYPE_NAME

    override fun getDescription(): String = "Audio file opened by AudioLens"

    override fun getDefaultExtension(): String = "wav"

    override fun getIcon(): Icon? = null

    override fun isBinary(): Boolean = true

    companion object {
        const val TYPE_NAME = "AudioLens Audio"

        @JvmField
        val INSTANCE = AudioLensFileType()

        val SUPPORTED_EXTENSIONS = setOf("wav", "mp3", "flac", "ogg", "opus", "m4a", "aac", "pcm", "raw", "ark")
    }
}
