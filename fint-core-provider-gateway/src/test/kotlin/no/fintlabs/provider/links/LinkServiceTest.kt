package no.fintlabs.provider.links

import io.mockk.every
import io.mockk.mockk
import no.fintlabs.provider.config.ProviderProperties
import no.novari.core.shared.model.ResourceRef
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.elev.ElevResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinkServiceTest {
    private val baseUrl = "https://test.felleskomponent.no"
    private val properties =
        mockk<ProviderProperties> {
            every { baseUrl } returns this@LinkServiceTest.baseUrl
        }

    private val linkService = LinkService(properties)

    @Test
    fun `resetSelfLink resets self links and generates absolute url's from existing identifiers`() {
        val resource =
            ElevResource().apply {
                brukernavn = Identifikator().apply { identifikatorverdi = "abc123" }
                elevnummer = Identifikator().apply { identifikatorverdi = "kafka123" }
                links["self"] = (0..10).map { Link.with("RandomInvalidLink") }
            }
        val resourceRef = ResourceRef("utdanning", "elev", "elev")
        val componentUrl = "$baseUrl/${resourceRef.toURI()}"

        linkService.resetSelfLinks(resourceRef, resource)

        assertTrue(resource.selfLinks.size == 2)
        assertTrue(resource.selfLinks.any { it.href == "$componentUrl/brukernavn/abc123" })
        assertTrue(resource.selfLinks.any { it.href == "$componentUrl/elevnummer/kafka123" })
    }

    @Test
    fun `resetSelfLink does not mutate identifikators`() {
        val brukernavnId = "UPPERCASE_123"
        val elevnummerId = "Test)382839!ifF"

        val resource =
            ElevResource().apply {
                brukernavn = Identifikator().apply { identifikatorverdi = brukernavnId }
                elevnummer = Identifikator().apply { identifikatorverdi = elevnummerId }
            }

        val resourceRef = ResourceRef("utdanning", "elev", "elev")

        linkService.resetSelfLinks(resourceRef, resource)

        assertTrue(resource.selfLinks.any { it.href.endsWith(brukernavnId) })
        assertTrue(resource.selfLinks.any { it.href.endsWith(elevnummerId) })
    }

    @Test
    fun `resetSelfLink sets an empty list of there are no identifiers`() {
        val resource = ElevResource()

        val resourceRef = ResourceRef("utdanning", "elev", "elev")

        linkService.resetSelfLinks(resourceRef, resource)

        assertTrue(resource.selfLinks.isEmpty())
    }
}
