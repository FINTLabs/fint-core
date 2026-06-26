package no.fintlabs.consumer.links

import no.fintlabs.model.resource.createFintResources
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.elev.ElevResource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FintResourcesTest {

    val baseUrl = "https://test.felleskomponent.no"
    val resourceUri = "utdanning/elev/elev"

    // If only one page, should only have self
    @Test
    fun `only self`() {
        val resources = (0..3).map {
            ElevResource()
        }
        // Page with 1-4 of 4 total
        val fintResources = createFintResources(
            baseUrl,
            resourceUri,
            resources,
            0,
            4,
            4
        )

        // Only the self link should be present
        val links: Map<String, List<Link>> = fintResources.getLinks()
        assertEquals(1, links.size)
        assertNotNull(links["self"])
    }

    // If on first page, should only have self and next
    @Test
    fun `self and next`() {
        val resources = (0..3).map {
            ElevResource()
        }
        // Page with 1-4 of 8 total
        val fintResources = createFintResources(
            baseUrl,
            resourceUri,
            resources,
            0,
            4,
            8
        )

        // Only self and next should be present
        val links: Map<String, List<Link>> = fintResources.getLinks()
        assertEquals(2, links.size)
        assertNotNull(links["self"])
        assertNotNull(links["next"])

    }

    // If on last page, should only have prev and self
    @Test
    fun `prev and self`() {
        val resources = (0..3).map {
            ElevResource()
        }
        // Page with 5-8 of 8 total
        val fintResources = createFintResources(
            baseUrl,
            resourceUri,
            resources,
            4,
            4,
            8
        )

        // Only self and prev should be present
        val links: Map<String, List<Link>> = fintResources.getLinks()
        assertEquals(2, links.size)
        assertNotNull(links["self"])
        assertNotNull(links["prev"])
    }

    // If on any page that is not first or last
    @Test
    fun `self, prev and next`() {
        val resources = (0..3).map {
            ElevResource()
        }
        // Page with 5-8 of 12 total
        val fintResources = createFintResources(
            baseUrl,
            resourceUri,
            resources,
            4,
            4,
            12
        )

        // self, prev and next should be present
        val links: Map<String, List<Link>> = fintResources.getLinks()
        assertEquals(3, links.size)
        assertNotNull(links["self"])
        assertNotNull(links["prev"])
        assertNotNull(links["next"])
    }
}
