package no.fintlabs.consumer.kafka.sync

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LastCompletedFullSyncCacheTest {
    private val cache = LastCompletedFullSyncCache()

    @Test
    fun `returns zero for unknown resource`() {
        assertEquals(0L, cache.getLatestFromResource("unknown"))
    }

    @Test
    fun `stores timestamp for resource`() {
        cache.registerTimestamp("elevfravar", 100L)

        assertEquals(100L, cache.getLatestFromResource("elevfravar"))
    }

    @Test
    fun `keeps newer timestamp when older one is registered later`() {
        cache.registerTimestamp("elevfravar", 200L)
        cache.registerTimestamp("elevfravar", 100L)

        assertEquals(200L, cache.getLatestFromResource("elevfravar"))
    }

    @Test
    fun `overwrites when a newer timestamp is registered`() {
        cache.registerTimestamp("elevfravar", 100L)
        cache.registerTimestamp("elevfravar", 200L)

        assertEquals(200L, cache.getLatestFromResource("elevfravar"))
    }

    @Test
    fun `tracks resources independently`() {
        cache.registerTimestamp("elevfravar", 100L)
        cache.registerTimestamp("fravar", 200L)

        assertEquals(100L, cache.getLatestFromResource("elevfravar"))
        assertEquals(200L, cache.getLatestFromResource("fravar"))
    }
}
