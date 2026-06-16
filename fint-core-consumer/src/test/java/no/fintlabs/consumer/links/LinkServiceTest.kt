package no.fintlabs.consumer.links

import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.links.nested.NestedLinkMapper
import no.fintlabs.consumer.links.nested.NestedLinkService
import no.fintlabs.consumer.resource.context.ResourceContext
import no.fintlabs.consumer.resource.context.ResourceContextCache
import no.fintlabs.reflection.ReflectionCache
import no.fintlabs.reflection.ReflectionInitializer
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.elev.ElevResource
import no.novari.fint.model.resource.utdanning.elev.KlasseResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig

@SpringJUnitConfig(classes = [LinkServiceTest.Config::class])
@TestPropertySource(
    properties = [
        "fint.consumer.base-url=https://test.felleskomponent.no",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package-name=elev",
        "fint.consumer.org-id=fintlabs.no",
        "fint.consumer.writeable=klasse",
        "fint.consumer.pod-url=http://test",
    ],
)
class LinkServiceTest {
    @EnableConfigurationProperties(ConsumerConfiguration::class)
    @Import(
        ReflectionCache::class,
        ReflectionInitializer::class,
        ResourceContextCache::class,
        ResourceContext::class,
        LinkGenerator::class,
        LinkPaginator::class,
        LinkParser::class,
        NestedLinkMapper::class,
        NestedLinkService::class,
        LinkService::class,
    )
    class Config

    @Autowired
    private lateinit var linkService: LinkService

    private val elevResourceName = "elev"
    private val baseUrl = "https://test.felleskomponent.no"
    private val elevComponentUrl = "$baseUrl/utdanning/elev"
    private val utdanningsprogramUrl = "$baseUrl/utdanning/utdanningsprogram"
    private val elevResourceUrl = "$elevComponentUrl/elev"

    @Test
    fun `toResources throws NPE when resources is null`() {
        assertThrows<NullPointerException> {
            linkService.toResources("elev", null, 0, 10, 0)
        }
    }

    @Nested
    inner class RemoveDuplicates {
        @Test
        fun `duplicates created by link mapping are reduced to one`() {
            val resource = ElevResource()
            resource.addPerson(Link.with("systemid/elev-1"))
            resource.addPerson(Link.with("\${elev}/systemid/elev-1"))
            linkService.mapLinks(elevResourceName, resource)
            val links = resource.links["person"]
            assertNotNull(links)
            assertEquals(1, links!!.size)
            assertEquals("$elevComponentUrl/person/systemid/elev-1", links[0].href)
        }

        @Test
        fun `unique link is preserved`() {
            val resource = ElevResource()
            resource.addPerson(Link.with("systemid/elev-1"))
            linkService.mapLinks(elevResourceName, resource)
            val links = resource.links["person"]
            assertNotNull(links)
            assertEquals(1, links!!.size)
        }

        @Test
        fun `distinct links are both preserved`() {
            val resource = ElevResource()
            resource.addPerson(Link.with("systemid/elev-1"))
            resource.addPerson(Link.with("systemid/elev-2"))
            linkService.mapLinks(elevResourceName, resource)
            val links = resource.links["person"]
            assertNotNull(links)
            assertEquals(2, links!!.size)
        }

        @Test
        fun `many duplicates collapsed to one`() {
            val resource = ElevResource()
            resource.addPerson(Link.with("systemid/elev-1"))
            resource.addPerson(Link.with("systemid/elev-1"))
            resource.addPerson(Link.with("systemid/elev-1"))
            linkService.mapLinks(elevResourceName, resource)
            val links = resource.links["person"]
            assertNotNull(links)
            assertEquals(1, links!!.size)
        }

        @Test
        fun `mixed links - duplicates removed unique kept`() {
            val resource = ElevResource()
            resource.addPerson(Link.with("systemid/elev-1"))
            resource.addPerson(Link.with("systemid/elev-1"))
            resource.addPerson(Link.with("systemid/elev-2"))
            linkService.mapLinks(elevResourceName, resource)
            val links = resource.links["person"]
            assertNotNull(links)
            assertEquals(2, links!!.size)
        }
    }

    // Self link tests

    @Nested
    inner class SelfLinks {
        @Test
        fun `should create self links when identificator is present`() {
            val elevResource = createElevResource("123")
            linkService.mapLinks(elevResourceName, elevResource)
            assertEquals("$elevResourceUrl/systemid/123", elevResource.selfLinks.first().href)
        }

        @Test
        fun `should reset self links`() {
            val elevResource = createElevResource("123")
            elevResource.addSelf(Link.with("shouldnt/exist"))
            elevResource.addSelf(Link.with("shouldnt/exist/either"))
            linkService.mapLinks(elevResourceName, elevResource)
            assertEquals(1, elevResource.selfLinks.size)
        }
    }

    // Relation link tests

    @Test
    fun `relation name is lowercased when generating link`() {
        val elevResource = createElevResource("123")
        val relationName = "elevfOrhold"
        elevResource.addLink(relationName, Link.with("/systemid/123"))
        linkService.mapLinks(elevResourceName, elevResource)
        assertEquals(
            "$elevComponentUrl/elevforhold/systemid/123",
            elevResource.links[relationName]!!.first().href,
        )
    }

    @Test
    fun `relative link with leading slash is resolved to full url`() {
        val elevResource = createElevResource("123")
        elevResource.addElevforhold(Link.with("/systemid/123"))
        linkService.mapLinks(elevResourceName, elevResource)
        assertEquals("$elevComponentUrl/elevforhold/systemid/123", elevResource.elevforhold.first().href)
    }

    @Test
    fun `relative link without leading slash is resolved to full url`() {
        val elevResource = createElevResource("123")
        elevResource.addElevforhold(Link.with("systemid/123"))
        linkService.mapLinks(elevResourceName, elevResource)
        assertEquals("$elevComponentUrl/elevforhold/systemid/123", elevResource.elevforhold.first().href)
    }

    @Test
    fun `absolute link is kept as-is`() {
        val elevResource = createElevResource("123")
        val linkSegment = "https://no-valid-url.com/whatever/ok/systemid/123"
        elevResource.addElevforhold(Link.with(linkSegment))
        linkService.mapLinks(elevResourceName, elevResource)
        assertEquals(linkSegment, elevResource.elevforhold.first().href)
    }

    @Test
    fun `link is resolved against correct component when relation belongs to another component`() {
        val klasseResource = createKlasse("123")
        klasseResource.addSkole(Link.with("systemid/123"))
        linkService.mapLinks("klasse", klasseResource)
        assertEquals("$utdanningsprogramUrl/skole/systemid/123", klasseResource.skole.first().href)
    }

    @Test
    fun `common relation link is resolved to full url`() {
        val elevResource = createElevResource("123")
        linkService.mapLinks(elevResourceName, elevResource)
        assertEquals("$elevComponentUrl/person/systemid/123", elevResource.person.first().href)
    }

    // Link behaviour tests

    @Test
    fun `relation is removed when all links are null`() {
        val elevResource = createElevResource("123")
        val relationName = "test"
        elevResource.addLink(relationName, null)
        elevResource.addLink(relationName, null)
        linkService.mapLinks(elevResourceName, elevResource)
        assertNull(elevResource.links[relationName])
    }

    @Test
    fun `relation is removed when all link hrefs are null`() {
        val elevResource = createElevResource("123")
        val relationName = "test"
        elevResource.addLink(relationName, Link.with(null as String?))
        elevResource.addLink(relationName, Link.with(null as String?))
        linkService.mapLinks(elevResourceName, elevResource)
        assertNull(elevResource.links[relationName])
    }

    @Test
    fun `null links are filtered out leaving valid links intact`() {
        val elevResource = createElevResource("123")
        val relationName = "test"
        elevResource.addLink(relationName, null)
        elevResource.addLink(relationName, Link.with("123"))
        linkService.mapLinks(elevResourceName, elevResource)
        assertEquals(1, elevResource.links[relationName]!!.size)
    }

    @Test
    fun `links with null href are filtered out leaving valid links intact`() {
        val elevResource = createElevResource("123")
        val relationName = "test"
        elevResource.addLink(relationName, Link.with(null as String?))
        elevResource.addLink(relationName, Link.with("123"))
        linkService.mapLinks(elevResourceName, elevResource)
        assertEquals(1, elevResource.links[relationName]!!.size)
    }

    private fun createKlasse(id: String): KlasseResource {
        val klasseResource = KlasseResource()
        klasseResource.systemId = Identifikator().apply { identifikatorverdi = id }
        klasseResource.addTrinn(Link.with("systemid/123"))
        klasseResource.addSkole(Link.with("systemid/123"))
        return klasseResource
    }

    private fun createElevResource(id: String): ElevResource {
        val elevResource = ElevResource()
        elevResource.systemId = Identifikator().apply { identifikatorverdi = id }
        elevResource.addPerson(Link.with("systemid/123"))
        return elevResource
    }
}
