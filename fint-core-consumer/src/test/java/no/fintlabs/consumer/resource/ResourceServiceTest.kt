package no.fintlabs.consumer.resource

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.novari.core.shared.json.FintModelModule
import no.novari.core.shared.model.ResourceCoordinate
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

    private val resourceService =
        ResourceService(
            consumerConfiguration =
                ConsumerConfiguration(
                    baseUrl = "https://api.felleskomponent.no",
                    orgIdValue = "fintlabs.no",
                    domain = "utdanning",
                    packageName = "elev",
                    podUrl = "http://localhost",
                ),
            resourceStore = resourceStore,
            objectMapper = ObjectMapper().registerKotlinModule().registerModule(FintModelModule()),
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
    fun `an unknown resource coordinate is rejected by the model`() {
        val unknown = ResourceCoordinate("fintlabs.no", "utdanning", "vurdering", "finnesikke")

        every { resourceStore.findAll(null, any()) } returns listOf(resourceEntry("resource-1"))

        assertThrows<IllegalArgumentException> {
            resourceService.getResources(unknown, 0, 0, null, null)
        }
    }

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
