package no.fintlabs.provider.links

import io.mockk.every
import io.mockk.mockk
import no.fintlabs.provider.config.ProviderProperties
import no.novari.core.shared.model.ResourceRef
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.elev.ElevResource
import no.novari.fint.model.utdanning.elev.Elevforhold
import no.novari.metamodel.ComponentBuilder
import no.novari.metamodel.MetamodelService
import no.novari.metamodel.ReflectionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LinkServiceTest {
    private val baseUrl = "https://test.felleskomponent.no"
    private val properties =
        mockk<ProviderProperties> {
            every { baseUrl } returns this@LinkServiceTest.baseUrl
        }

    private val reflectionService = ReflectionService()
    private val componentBuilder = ComponentBuilder(reflectionService)
    private val metamodelService = MetamodelService(componentBuilder)
    private val linkService = LinkService(properties, metamodelService)

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

    @Test
    fun `mapLinks set correct HATEOAS links`() {
        val resource = ElevResource()

        resource.brukernavn = Identifikator().apply { identifikatorverdi = "Test" }
        resource.elevnummer = Identifikator().apply { identifikatorverdi = "456" }
        resource.addPerson(Link.with("fodselsnummer/123"))
        resource.addPerson(null)
        resource.addPerson(Link(null))
        resource.addPerson(Link(""))

        val resourceRef = ResourceRef("utdanning", "elev", "elev")
        val componentUrl = "$baseUrl/${resourceRef.toURI()}"

        linkService.mapLinks(resourceRef, resource)

        assertEquals("$componentUrl/fodselsnummer/123", resource.person.first().href)

        assertEquals(2, resource.selfLinks.size) // Assert that both identificators got links
        assertTrue(resource.selfLinks.any { it.href == "$componentUrl/brukernavn/Test" })
        assertTrue(resource.selfLinks.any { it.href == "$componentUrl/elevnummer/456" })

        assertEquals(1, resource.person.size) // Assert that invalid links are removed
    }

    @Test
    fun `mapLinks keeps case in identifiers and lower cases in the rest of the link`() {
        val resource = ElevResource()

        resource.brukernavn = Identifikator().apply { identifikatorverdi = "Test" }
        resource.elevnummer = Identifikator().apply { identifikatorverdi = "456" }
        resource.addElevforhold(Link.with("SYstEmID/ABCdef")) // "https://.../systemid/ABCdef
        resource.addPerson(Link.with("fodselsnummer/123"))

        val resourceRef = ResourceRef("utdanning", "elev", "elev")
        val componentUrl = "$baseUrl/${resourceRef.toURI()}"

        linkService.mapLinks(resourceRef, resource)

        assertEquals("$baseUrl/utdanning/elev/elevforhold/systemid/ABCdef", resource.elevforhold.first().href)
        assertTrue(resource.selfLinks.any { it.href == "$componentUrl/brukernavn/Test" })
    }

    @Test
    fun `Unknown resourceRef throws exception`() {
        val resourceRef = ResourceRef("utdanning", "vurdering", "not-a-resource")
        assertThrows<RuntimeException> { linkService.mapLinks(resourceRef, ElevResource()) }
    }
}
