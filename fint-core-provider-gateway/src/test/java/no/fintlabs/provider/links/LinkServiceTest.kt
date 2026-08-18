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
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.assertThrows

class LinkServiceTest {
    private val baseUrl = "https://test.felleskomponent.no"
    private val properties =
        mockk<ProviderProperties> {
            every { baseUrl } returns this@LinkServiceTest.baseUrl
        }

    private val linkService = LinkService()

    @Test
    fun `invalid links are removed`() {
        val resource =
            ElevResource().apply {
                brukernavn = Identifikator().apply { identifikatorverdi = "abc123" }
                elevnummer = Identifikator().apply { identifikatorverdi = "kafka123" }
                links["self"] = (0..10).map { Link.with("RandomInvalidLink") }
                addElevforhold(Link.with("RandomInvalidLink"))
                addElevforhold(Link.with("valid/link"))
                addPerson(Link.with("RandomInvalidLink"))
            }
        linkService.mapLinks(resource)
        assertEquals(1, resource.links["elevforhold"]?.size)
        assertNull(resource.links["person"])
        assertNull(resource.links["self"])
    }

    @Test
    fun `links are formatted to correct relative URI`() {
        val resource =
            ElevResource().apply {
                brukernavn = Identifikator().apply { identifikatorverdi = "abc123" }
                elevnummer = Identifikator().apply { identifikatorverdi = "kafka123" }
                addElevforhold(Link.with("http://alpha.felleskomponent.no/valid/link"))
            }
        linkService.mapLinks(resource)
        assertEquals("valid/link", resource.links["elevforhold"]?.first().toString())
    }
}
