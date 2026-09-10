package no.fintlabs.provider.sync

import com.mongodb.client.MongoClients
import no.fintlabs.provider.mongoTestContainer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import kotlin.test.assertEquals

@Testcontainers
class SyncProgressStoreIT {
    companion object {
        @Container
        @JvmStatic
        val MONGO = mongoTestContainer()
    }

    @Test
    fun `starts when the legacy ttl index already exists`() {
        val template = MongoTemplate(MongoClients.create(MONGO.connectionString), "sync-progress-store-it")
        template.indexOps(SyncProgressStore.COLLECTION_NAME).createIndex(
            Index()
                .on("updatedAt", Sort.Direction.ASC)
                .named("progress_ttl_idx")
                .expire(Duration.ofHours(24)),
        )

        assertDoesNotThrow { SyncProgressStore(template) }
        assertEquals(
            setOf("_id_", "progress_ttl_idx"),
            template
                .indexOps(SyncProgressStore.COLLECTION_NAME)
                .indexInfo
                .map { it.name }
                .toSet(),
        )
    }
}
