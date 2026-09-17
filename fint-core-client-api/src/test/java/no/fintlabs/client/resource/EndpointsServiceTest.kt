package no.fintlabs.client.resource

import no.fintlabs.client.config.ConsumerConfiguration
import no.novari.fint.core.model.FintModel
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EndpointsServiceTest {
    private val baseUrl = "https://beta.felleskomponent.no"

    private val endpointsService =
        EndpointsService(
            ConsumerConfiguration(
                baseUrl = baseUrl,
                orgIdValue = "fintlabs.no",
                domain = "utdanning",
                packageName = "vurdering",
                podUrl = "http://localhost",
            ),
        )

    @Test
    fun `the overview has one entry per resource the model serves in the component`() {
        val expected =
            FintModel
                .resourcesIn("utdanning", "vurdering")
                .map { it.ref.resourceName }
                .toSet()

        val result = endpointsService.componentOverview("utdanning", "vurdering")

        assertEquals(expected, result.keys)
    }

    @Test
    fun `every url for a resource is built from the base url and the served path`() {
        val result = endpointsService.componentOverview("utdanning", "vurdering")

        val karakterverdi = result.getValue("karakterverdi")
        assertEquals("$baseUrl/utdanning/vurdering/karakterverdi", karakterverdi.collectionUrl)
        assertEquals("$baseUrl/utdanning/vurdering/karakterverdi/last-updated", karakterverdi.lastUpdatedUrl)
        assertEquals("$baseUrl/utdanning/vurdering/karakterverdi/cache/size", karakterverdi.cacheSizeUrl)
        assertEquals(listOf("$baseUrl/utdanning/vurdering/karakterverdi/systemid/{id:.+}"), karakterverdi.oneUrl)
    }

    @Test
    fun `a common resource is served under the component it is reached from`() {
        val result = endpointsService.componentOverview("utdanning", "elev")

        val person = result.getValue("person")
        assertEquals("$baseUrl/utdanning/elev/person", person.collectionUrl)
        assertEquals(listOf("$baseUrl/utdanning/elev/person/fodselsnummer/{id:.+}"), person.oneUrl)
    }

    @Test
    fun `an iso code list keeps its extra path segment in the urls but not in the key`() {
        val result = endpointsService.componentOverview("felles", "kodeverk")

        val landkode = result.getValue("landkode")
        assertEquals("$baseUrl/felles/kodeverk/iso/landkode", landkode.collectionUrl)
    }
}
