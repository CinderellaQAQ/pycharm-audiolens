package audiolens.pycharm.web

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class PayloadStore {
    private data class Entry(val bytes: ByteArray, val expiresAt: Long)

    private val values = ConcurrentHashMap<String, Entry>()
    private val totalBytes = AtomicLong()

    fun put(bytes: ByteArray): String {
        require(bytes.size <= MAX_ITEM_BYTES) { "AudioLens response is too large." }
        cleanup()
        require(totalBytes.get() + bytes.size <= MAX_TOTAL_BYTES) { "Too many pending AudioLens responses." }
        val id = randomToken(18)
        values[id] = Entry(bytes, System.currentTimeMillis() + TTL_MS)
        totalBytes.addAndGet(bytes.size.toLong())
        return id
    }

    fun take(id: String): ByteArray? {
        val entry = values.remove(id) ?: return null
        totalBytes.addAndGet(-entry.bytes.size.toLong())
        return entry.bytes.takeIf { entry.expiresAt >= System.currentTimeMillis() }
    }

    fun clear() {
        values.clear()
        totalBytes.set(0)
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        values.entries.removeIf { (_, entry) ->
            if (entry.expiresAt >= now) return@removeIf false
            totalBytes.addAndGet(-entry.bytes.size.toLong())
            true
        }
    }

    companion object {
        private const val MAX_ITEM_BYTES = 32 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 96 * 1024 * 1024
        private const val TTL_MS = 60_000L
        private val random = SecureRandom()

        fun randomToken(bytes: Int): String {
            val value = ByteArray(bytes)
            random.nextBytes(value)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value)
        }
    }
}
