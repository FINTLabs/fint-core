package no.fintlabs.provider.sync

import no.fintlabs.adapter.models.sync.SyncPage
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.sync.SyncPageMetadata
import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.provider.ProviderAppIT
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.RelationEdge
import org.awaitility.kotlin.await
import org.bson.Document
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria.where
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Query.query
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The broker-level half of autorelation: an adapter-shaped payload with an href link goes
 * through the real Kafka pipeline and comes out as a relation edge. How the rules and the
 * store behave is tested in [AutoRelationIT], off the broker.
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

    @Test
    fun `a delete sync removes the resource and the edges it created`() {
        val edgeCollection = "fintlabs_no_relation_edges"
        val resourceCollection = "fintlabs_no_utdanning_elev_elevforhold"
        val coordinate = ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elevforhold")
        val saved =
            SyncPageEntry.of(
                "EF-DEL",
                mapOf(
                    "systemId" to mapOf("identifikatorverdi" to "EF-DEL"),
                    "_links" to
                        mapOf(
                            "elev" to
                                listOf(
                                    mapOf("href" to "https://api.felleskomponent.no/utdanning/elev/elev/elevnummer/E-789"),
                                ),
                        ),
                ),
            )

        writer.sendSyncEntity(syncPage("corr-id-delete-save", SyncType.DELTA, saved), saved, coordinate).get()
        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            assertNotNull(mongoTemplate.findById("EF-DEL", Document::class.java, resourceCollection))
            assertEquals(1, edgesFrom("EF-DEL", edgeCollection).size)
        }

        val deleted = SyncPageEntry.of("EF-DEL", null)
        writer.sendSyncEntity(syncPage("corr-id-delete", SyncType.DELETE, deleted), deleted, coordinate).get()
        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            assertNull(mongoTemplate.findById("EF-DEL", Document::class.java, resourceCollection))
            assertTrue(edgesFrom("EF-DEL", edgeCollection).isEmpty())
        }
    }

    private fun syncPage(
        corrId: String,
        type: SyncType,
        entry: SyncPageEntry,
    ) = SyncPage(
        SyncPageMetadata(
            "test",
            corrId,
            "fintlabs-no",
            1L,
            1L,
            1L,
            1L,
            "beta.felleskomponent.no/utdanning/elev",
            1782300748715L,
        ),
        listOf(entry),
        type,
    )

    private fun edgesFrom(
        sourceId: String,
        edgeCollection: String,
    ): List<RelationEdge> = mongoTemplate.find(query(where("sourceId").`is`(sourceId)), RelationEdge::class.java, edgeCollection)
}
