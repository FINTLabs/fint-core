package no.fintlabs.provider.sync

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.fint.model.resource.utdanning.elev.ElevResource
import no.novari.metamodel.MetamodelService
import no.novari.metamodel.model.Component
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import no.novari.metamodel.model.Resource as MetamodelResource

class ResourceConverterTest {
    private val coords =
        ResourceCoordinate(
            orgId = "fintlabs.no",
            domainName = "utdanning",
            packageName = "elev",
            resourceName = "elev",
        )

    private val metamodelResource =
        MetamodelResource(
            name = "elev",
            component = Component("utdanning", "elev"),
            className = ElevResource::class.java.name,
            resourceClass = ElevResource::class.java,
            isCommon = false,
            writeable = false,
            fields = emptySet(),
            idFields = emptySet(),
            relations = emptyList(),
        )

    private val metaModelService =
        mockk<MetamodelService> {
            every { getResource("utdanning", "elev", "elev") } returns metamodelResource
        }

    private val converter = ResourceConverter(ObjectMapper(), metaModelService)

    @Test
    fun `converts JSON string payload to FintResource`() {
        val json =
            """
            {
              "systemId": {
                "identifikatorverdi": "123"
              },
              "elevnummer": {
                "identifikatorverdi": "ELEV-123"
              }
            }
            """.trimIndent()

        val converted = assertIs<ElevResource>(converter.convert(coords, json))

        assertEquals("123", converted.systemId.identifikatorverdi)
        assertEquals("ELEV-123", converted.elevnummer.identifikatorverdi)
    }
}
