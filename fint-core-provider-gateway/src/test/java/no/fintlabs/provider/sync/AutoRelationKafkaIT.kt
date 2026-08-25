package no.fintlabs.provider.sync

import no.fintlabs.adapter.models.sync.SyncPage
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.sync.SyncPageMetadata
import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.provider.ProviderAppIT
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.RelationEdge
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria.where
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Query.query
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The broker-level half of autorelation: an adapter-shaped payload with an href link goes
 * through the real Kafka pipeline and comes out as a relation edge. The rule and store
 * semantics live in [AutoRelationIT], off the broker.
 */
class AutoRelationKafkaIT(
    @Autowired private val writer: BufferWriter,
    @Autowired private val mongoTemplate: MongoTemplate,
) : ProviderAppIT() {
    @Test
    fun `a qualifying link on a buffered resource becomes a relation edge`() {
        val edgeCollection = "fintlabs_no_relation_edges"
        mongoTemplate.remove(Query(), edgeCollection)

        val resource =
            SyncPageEntry.of(
                "EF-123",
                mapOf(
                    "systemId" to mapOf("identifikatorverdi" to "EF-123"),
                    "_links" to
                        mapOf(
                            "elev" to
                                listOf(
                                    mapOf(
                                        "href" to
                                            "https://api.felleskomponent.no/utdanning/elev/elev/elevnummer/E-456",
                                    ),
                                ),
                        ),
                ),
            )

        val resourceCoordinate =
            ResourceCoordinate(
                "fintlabs.no",
                "utdanning",
                "elev",
                "elevforhold",
            )

        val sync =
            SyncPage(
                SyncPageMetadata(
                    "test",
                    "corr-id-edge-test",
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
            val edge =
                mongoTemplate.findOne(
                    query(
                        where("targetType")
                            .`is`("utdanning/elev/elev")
                            .and("targetIdField")
                            .`is`("elevnummer")
                            .and("targetIdValue")
                            .`is`("E-456"),
                    ),
                    RelationEdge::class.java,
                    edgeCollection,
                )

            assertNotNull(edge, "the relation edge was not persisted")
            assertEquals("elevforhold", edge.inverseName)
            assertEquals("systemid", edge.sourceIdField)
            assertEquals("EF-123", edge.sourceIdValue)
            assertNotNull(edge.createdAt)
        }
    }
}
