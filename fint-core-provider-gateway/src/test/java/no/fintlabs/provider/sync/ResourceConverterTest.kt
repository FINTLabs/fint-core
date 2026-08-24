package no.fintlabs.provider.sync

import no.novari.core.shared.model.ResourceCoordinate
import no.novari.fint.core.model.utdanning.elev.Elev
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ResourceConverterTest {
    private val coords =
        ResourceCoordinate(
            orgId = "fintlabs.no",
            domainName = "utdanning",
            packageName = "elev",
            resourceName = "elev",
        )

    private val converter = ResourceConverter()

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

        val converted = assertIs<Elev>(converter.convert(coords, json))

        assertEquals("123", converted.systemId?.identifikatorverdi)
        assertEquals("ELEV-123", converted.elevnummer?.identifikatorverdi)
    }
}
