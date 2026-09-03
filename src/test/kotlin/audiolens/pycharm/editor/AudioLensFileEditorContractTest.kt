package audiolens.pycharm.editor

import com.intellij.openapi.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioLensFileEditorContractTest {
    @Test
    fun `every AudioLens editor provides its virtual file`() {
        val editorClasses = listOf(
            AudioLensFileEditor::class.java,
            AudioLensRemoteFileEditor::class.java,
            AudioLensUnavailableEditor::class.java,
        )

        for (editorClass in editorClasses) {
            val method = editorClass.getDeclaredMethod("getFile")
            assertEquals(VirtualFile::class.java, method.returnType)
            assertEquals(editorClass, method.declaringClass)
        }
    }
}
