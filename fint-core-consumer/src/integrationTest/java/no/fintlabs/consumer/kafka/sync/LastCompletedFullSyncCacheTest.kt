package no.fintlabs.consumer.kafka.sync

import no.fintlabs.config.MongoTestcontainerInitializer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import kotlin.test.assertEquals

/**
 * Exercises the Mongo-backed [LastCompletedFullSyncCache] against a Testcontainers Mongo instance,
 * brought up by [MongoTestcontainerInitializer].
 */
class LastCompletedFullSyncCacheTest {
    private lateinit var cache: LastCompletedFullSyncCache

    @BeforeEach
    fun setUp() {
        val factory =
            SimpleMongoClientDatabaseFactory(
                MongoTestcontainerInitializer.MONGO.getReplicaSetUrl("sync-full-completed-test"),
            )
        val mongoTemplate = MongoTemplate(factory)
        mongoTemplate.dropCollection(LastCompletedFullSyncCache.COLLECTION)
        cache = LastCompletedFullSyncCache(mongoTemplate)
    }

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
