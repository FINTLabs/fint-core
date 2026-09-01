package no.fintlabs.consumer.resource

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.consumer.config.AutorelationConfig
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.RelationEdge
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.relation.relationEdgeId
import no.novari.core.shared.store.IdentifierRef
import no.novari.core.shared.store.ResourceEntry
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.core.model.utdanning.vurdering.Elevfravar
import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class ResourceServiceTest {
    private val resourceStore = mockk<ResourceStore>()

    private val relationEdgeStore =
        mockk<RelationEdgeStore> {
            every { findByTargets(any(), any(), any()) } returns emptyList()
            every { findAllByTargetType(any(), any()) } returns emptyList()
        }

    private val resourceService =
        ResourceService(
            consumerConfiguration = consumerConfiguration(),
            resourceStore = resourceStore,
            relationEdgeStore = relationEdgeStore,
        )

    val resourceCoordinate =
        ResourceCoordinate(
            "fintlabs.no",
            "utdanning",
            "vurdering",
            "elevfravar",
        )

    @Test
    fun `getResources calls findAll when size is 0`() {
        every {
            resourceStore.findAll(null, "fintlabs_no_utdanning_vurdering_elevfravar")
        } returns emptyList()

        val result = resourceService.getResources(resourceCoordinate, 0, 0, null, null)

        verify(exactly = 1) {
            resourceStore.findAll(null, "fintlabs_no_utdanning_vurdering_elevfravar")
        }
        assertNotNull(result)
    }

    /**
     * Mocks 5 entries in the database
     * Should only return 2 when size is 2
     */
    @Test
    fun `getResources with size returns expected amount`() {
        val collectionName = "fintlabs_no_utdanning_vurdering_elevfravar"
        val entries = (1..5).map { resourceEntry("resource-$it") }

        every {
            resourceStore.findPage(null, 2, 0, collectionName)
        } returns entries.take(2)

        val result = resourceService.getResources(resourceCoordinate, 2, 0, null, null)

        verify(exactly = 1) {
            resourceStore.findPage(null, 2, 0, collectionName)
        }
        assertEquals(2, result.size)
    }

    // TODO: implement filtering
    @Test
    fun `getResources with filter returns as expected`() {
    }

    @Test
    fun `getResourceById gives correct entity by id`() {
        val collectionName = "fintlabs_no_utdanning_vurdering_elevfravar"
        val systemId = "resource-2"
        val entry =
            resourceEntry(
                id = "stored-resource-id",
                data = Document("systemId", Document("identifikatorverdi", systemId)),
                identifiers = listOf(IdentifierRef("systemId", systemId)),
            )

        every {
            resourceStore.findByIdentifier("systemId", systemId, collectionName)
        } returns entry

        val result = resourceService.getResourceById(resourceCoordinate, "systemId", systemId)

        verify(exactly = 1) {
            resourceStore.findByIdentifier("systemId", systemId, collectionName)
        }

        assertNotNull(result)
        val elevfravar = result as Elevfravar
        assertEquals(systemId, elevfravar.systemId?.identifikatorverdi)
    }

    @Test
    fun `getResourceById merges relation edges into the resource links`() {
        val collectionName = "fintlabs_no_utdanning_vurdering_elevfravar"
        val systemId = "EF-1"
        val entry =
            resourceEntry(
                id = systemId,
                data = Document("systemId", Document("identifikatorverdi", systemId)),
                identifiers = listOf(IdentifierRef("systemid", systemId)),
            )

        every { resourceStore.findByIdentifier("systemid", systemId, collectionName) } returns entry
        every {
            relationEdgeStore.findByTargets(
                "fintlabs_no_relation_edges",
                "utdanning/vurdering/elevfravar",
                listOf(IdentifierRef("systemid", systemId)),
            )
        } returns
            listOf(
                RelationEdge(
                    id =
                        relationEdgeId(
                            sourceType = "utdanning/vurdering/fravarsregistrering",
                            sourceId = "FR-9",
                            relationName = "elevfravar",
                            targetType = "utdanning/vurdering/elevfravar",
                            targetIdField = "systemid",
                            targetIdValue = systemId,
                        ),
                    sourceType = "utdanning/vurdering/fravarsregistrering",
                    sourceId = "FR-9",
                    sourceIdField = "systemid",
                    sourceIdValue = "FR-9",
                    inverseName = "fravarsregistrering",
                    targetType = "utdanning/vurdering/elevfravar",
                    targetIdField = "systemid",
                    targetIdValue = systemId,
                ),
            )

        val result = resourceService.getResourceById(resourceCoordinate, "systemid", systemId)

        assertNotNull(result)
        val links = result.links["fravarsregistrering"]
        assertNotNull(links)
        assertEquals(1, links.size)
        assertEquals("systemid", links.first().idField)
        assertEquals("FR-9", links.first().idValue)
    }

    @Test
    fun `disabled autorelation never queries the edge store`() {
        val disabledService =
            ResourceService(
                consumerConfiguration = consumerConfiguration(autorelationEnabled = false),
                resourceStore = resourceStore,
                relationEdgeStore = relationEdgeStore,
            )
        val collectionName = "fintlabs_no_utdanning_vurdering_elevfravar"
        val entry =
            resourceEntry(
                id = "EF-2",
                data = Document("systemId", Document("identifikatorverdi", "EF-2")),
                identifiers = listOf(IdentifierRef("systemid", "EF-2")),
            )

        every { resourceStore.findByIdentifier("systemid", "EF-2", collectionName) } returns entry

        disabledService.getResourceById(resourceCoordinate, "systemid", "EF-2")

        verify(exactly = 0) { relationEdgeStore.findByTargets(any(), any(), any()) }
        verify(exactly = 0) { relationEdgeStore.findAllByTargetType(any(), any()) }
    }

    @Test
    fun `a full dump fetches all edges for the type`() {
        val collectionName = "fintlabs_no_utdanning_vurdering_elevfravar"
        every { resourceStore.findAll(null, collectionName) } returns
            listOf(resourceEntry("r-1", identifiers = listOf(IdentifierRef("systemid", "r-1"))))

        resourceService.getResources(resourceCoordinate, 0, 0, null, null)

        verify(exactly = 1) { relationEdgeStore.findAllByTargetType(any(), any()) }
        verify(exactly = 0) { relationEdgeStore.findByTargets(any(), any(), any()) }
    }

    @Test
    fun `a delta poll with size 0 queries edges by identifier`() {
        val collectionName = "fintlabs_no_utdanning_vurdering_elevfravar"
        every { resourceStore.findAll(any(), collectionName) } returns
            listOf(resourceEntry("r-1", identifiers = listOf(IdentifierRef("systemid", "r-1"))))

        resourceService.getResources(resourceCoordinate, 0, 0, 1723456789L, null)

        verify(exactly = 1) { relationEdgeStore.findByTargets(any(), any(), any()) }
        verify(exactly = 0) { relationEdgeStore.findAllByTargetType(any(), any()) }
    }

    @Test
    fun `an explicit page above 1000 still queries edges by identifier`() {
        val collectionName = "fintlabs_no_utdanning_vurdering_elevfravar"
        val entries = (1..1200).map { resourceEntry("r-$it", identifiers = listOf(IdentifierRef("systemid", "r-$it"))) }
        every { resourceStore.findPage(null, 1200, 0, collectionName) } returns entries

        resourceService.getResources(resourceCoordinate, 1200, 0, null, null)

        verify(exactly = 1) { relationEdgeStore.findByTargets(any(), any(), any()) }
        verify(exactly = 0) { relationEdgeStore.findAllByTargetType(any(), any()) }
    }

    @Test
    fun `an unknown resource coordinate is rejected by the model`() {
        val unknown = ResourceCoordinate("fintlabs.no", "utdanning", "vurdering", "finnesikke")

        every { resourceStore.findAll(null, any()) } returns listOf(resourceEntry("resource-1"))

        assertThrows<IllegalArgumentException> {
            resourceService.getResources(unknown, 0, 0, null, null)
        }
    }

    private fun consumerConfiguration(autorelationEnabled: Boolean = true) =
        ConsumerConfiguration(
            baseUrl = "https://api.felleskomponent.no",
            orgIdValue = "fintlabs.no",
            domain = "utdanning",
            packageName = "elev",
            podUrl = "http://localhost",
            autorelation = AutorelationConfig(enabled = autorelationEnabled),
        )

    private fun resourceEntry(id: String) =
        ResourceEntry(
            id = id,
            data = Document(),
            identifiers = emptyList(),
            createdAt = Instant.EPOCH,
            lastModified = Instant.EPOCH,
        )

    private fun resourceEntry(
        id: String,
        data: Document = Document(),
        identifiers: List<IdentifierRef> = emptyList(),
    ) = ResourceEntry(
        id = id,
        data = data,
        identifiers = identifiers,
        createdAt = Instant.EPOCH,
        lastModified = Instant.EPOCH,
    )
}
