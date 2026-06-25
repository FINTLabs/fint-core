package no.fintlabs.consumer.links

import no.fintlabs.model.resource.FintResources
import no.fintlabs.model.resource.createFintResources
import no.novari.fint.model.resource.utdanning.elev.ElevResource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import kotlin.test.assertEquals

class FintResourcesTest {

    val baseUrl = "https://test.felleskomponent.no"
    val resourceUri = "utdanning/elev/elev"

    //TODO: Continue here
    // If only one page, should only have self
    @Test
    fun `only self`() {
        val resources = (0..4).map {
            ElevResource()
        }
        val fintResources = createFintResources(
            baseUrl,
            resourceUri,
            resources,
            0,
            4,
            4
        )
        assertEquals(0, fintResources.offset)

    }

    // If on first page, should only have self and next
    @Test
    fun `self and next`() {

    }

    // If on last page, should only have prev and self
    @Test
    fun `prev and self`() {

    }

    // If on any page that is not first or last
    @Test
    fun `self, prev and next`() {

    }
}
