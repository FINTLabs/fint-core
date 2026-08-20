package no.fintlabs.provider.sync

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import no.novari.core.shared.json.FintModelModule
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

    private val mapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(FintModelModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)

    private val converter = ResourceConverter(mapper)

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
