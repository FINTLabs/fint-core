package no.fintlabs.consumer.resource

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.ResourceEntry
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.model.resource.utdanning.vurdering.ElevfravarResource
import no.novari.metamodel.MetamodelService
import no.novari.metamodel.model.Component
import no.novari.metamodel.model.Resource
import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import java.time.Instant

class ResourceServiceTest {
    private val resourceStore = mockk<ResourceStore>()
    private val metamodelService = mockk<MetamodelService>()

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
            metamodelService = metamodelService,
            objectMapper = ObjectMapper(),
        )
    val resourceCoordinate =
        ResourceCoordinate(
            "fintlabs.no",
            "utdanning",
            "vurdering",
            "elevfravar",
        )

    // Test for getResources
    @Test
    fun `getResources calls findAll when size is 0`() {
        every {
            metamodelService.getResource("utdanning", "vurdering", "elevfravar")
        } returns elevFravarMetaResource()
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
            metamodelService.getResource("utdanning", "vurdering", "elevfravar")
        } returns elevFravarMetaResource()
        every {
            resourceStore.findPage(null, 2, 0, collectionName)
        } returns entries.take(2)

        val result = resourceService.getResources(resourceCoordinate, 2, 0, null, null)

        verify(exactly = 1) {
            resourceStore.findPage(null, 2, 0, collectionName)
        }
        assertEquals(2, result.size)
    }

    @Test
    fun `getResources with filter returns as expected`() {
    }

    // Test for getResourceById

    // Test for getLastUpdated

    // Test for getCacheSize

    private fun elevFravarMetaResource() =
        Resource(
            name = "elevfravar",
            component = Component("utdanning", "vurdering"),
            className = ElevfravarResource::class.java.name,
            resourceClass = ElevfravarResource::class.java,
            isCommon = false,
            writeable = true,
            fields = emptySet(),
            idFields = setOf("systemId"),
            relations = emptyList(),
        )

    private fun resourceEntry(id: String) =
        ResourceEntry(
            id = id,
            data = Document(),
            identifiers = emptyList(),
            createdAt = Instant.EPOCH,
            lastModified = Instant.EPOCH,
        )
}
