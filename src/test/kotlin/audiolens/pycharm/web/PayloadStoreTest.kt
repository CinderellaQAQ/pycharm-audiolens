package audiolens.pycharm.web

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class PayloadStoreTest {
    @Test
    fun `payload links are one time values`() {
        val store = PayloadStore()
        val id = store.put(byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), store.take(id))
        assertNull(store.take(id))
    }
}
