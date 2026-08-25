package no.fintlabs.provider.sync

import no.fintlabs.adapter.models.sync.SyncPage
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.sync.SyncPageMetadata
import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.provider.ProviderAppIT
import no.novari.core.shared.model.ResourceCoordinate
import org.awaitility.kotlin.await
import org.bson.Document
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.MongoTemplate
import java.time.Duration
import kotlin.test.assertNotNull

class BufferIT(
    @Autowired private val writer: BufferWriter,
    @Autowired private val mongoTemplate: MongoTemplate,
) : ProviderAppIT() {
    @Test
    fun `a synced resource is consumed from the buffer and persisted`() {
        val resource = SyncPageEntry.of("123", createElev())

        val resourceCoordinate =
            ResourceCoordinate(
                "fintlabs.no",
                "utdanning",
                "elev",
                "elev",
            )
        val sync =
            SyncPage(
                SyncPageMetadata(
                    "test",
                    "corr-id-random",
                    "fintlabs-no",
                    1L,
                    1L,
                    1L,
                    1L,
                    "beta.felleskomponent.no/utdanning/elev",
                    1782300748715L,
                ),
                listOf(resource),
                SyncType.FULL,
            )

        writer.sendSyncEntity(sync, resource, resourceCoordinate).get()

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            val storedResource =
                mongoTemplate.findById(
                    "123",
                    Document::class.java,
                    resourceCoordinate.toCollectionName(),
                )
            assertNotNull(storedResource, "the resource document was not persisted")
        }
    }

    private fun createElev(): Map<String, Any> = mapOf("systemId" to mapOf("identifikatorverdi" to "123"))
}
