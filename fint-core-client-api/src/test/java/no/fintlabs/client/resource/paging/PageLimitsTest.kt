package no.fintlabs.client.resource.paging

import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.model.toResourceClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class PageLimitsTest {
    @Test
    fun `a resource with large entries has a page limit of its own`() {
        assertEquals(1, PageLimits.maxSizeOf(coordinate("utdanning/utdanningsprogram/skole")))
        assertEquals(100, PageLimits.maxSizeOf(coordinate("utdanning/vurdering/elevfravar")))
        assertEquals(500, PageLimits.maxSizeOf(coordinate("utdanning/elev/elev")))
    }

    @Test
    fun `a resource without a page limit of its own has the default`() {
        assertEquals(1000, PageLimits.maxSizeOf(coordinate("utdanning/elev/klassemedlemskap")))
        assertEquals(1000, PageLimits.maxSizeOf(coordinate("arkiv/noark/sak")))
    }

    @Test
    fun `three personal resources have a page limit above the default`() {
        LARGE_PAGE_RESOURCES.forEach { resource ->
            assertEquals(64000, PageLimits.maxSizeOf(coordinate(resource)), resource)
        }
    }

    @Test
    fun `every other page limit is from one entry to the default`() {
        (PageLimits.maxSizes - LARGE_PAGE_RESOURCES).forEach { (resource, maxSize) ->
            assertTrue(maxSize in 1..PageLimits.DEFAULT, "$resource has the page limit $maxSize")
        }
    }

    @Test
    fun `every resource with a page limit of its own is in the information model`() {
        PageLimits.maxSizes.keys.forEach { resource ->
            assertDoesNotThrow("$resource is not in the information model") { coordinate(resource).toResourceClass() }
        }
    }

    private fun coordinate(resourceUri: String): ResourceCoordinate {
        val (domainName, packageName, resourceName) = resourceUri.split("/")
        return ResourceCoordinate("fintlabs.no", domainName, packageName, resourceName)
    }

    private companion object {
        val LARGE_PAGE_RESOURCES =
            setOf(
                "administrasjon/personal/arbeidsforhold",
                "administrasjon/personal/person",
                "administrasjon/personal/personalressurs",
            )
    }
}
