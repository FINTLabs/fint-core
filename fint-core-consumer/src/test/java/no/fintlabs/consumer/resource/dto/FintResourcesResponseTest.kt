package no.fintlabs.consumer.resource.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FintResourcesResponseTest {
    private val baseUrl = "https://test.felleskomponent.no"
    private val resourceUri = "utdanning/elev/elev"
    private val mapper = ObjectMapper().registerKotlinModule()

    private fun page(
        entryCount: Int,
        offset: Long,
        size: Int,
        totalItems: Int,
    ) = createFintResourcesResponse(
        baseUrl,
        resourceUri,
        (1..entryCount).map { mapOf("n" to it) },
        offset,
        size,
        totalItems,
    )

    // If only one page, should only have self
    @Test
    fun `only self`() {
        val links = page(entryCount = 4, offset = 0, size = 4, totalItems = 4).links

        assertEquals(1, links.size)
        assertNotNull(links["self"])
    }

    // If on first page, should only have self and next
    @Test
    fun `self and next`() {
        val links = page(entryCount = 4, offset = 0, size = 4, totalItems = 8).links

        assertEquals(2, links.size)
        assertNotNull(links["self"])
        assertNotNull(links["next"])
    }

    // If on last page, should only have prev and self
    @Test
    fun `prev and self`() {
        val links = page(entryCount = 4, offset = 4, size = 4, totalItems = 8).links

        assertEquals(2, links.size)
        assertNotNull(links["self"])
        assertNotNull(links["prev"])
    }

    // If on any page that is not first or last
    @Test
    fun `self, prev and next`() {
        val links = page(entryCount = 4, offset = 4, size = 4, totalItems = 12).links

        assertEquals(3, links.size)
        assertNotNull(links["self"])
        assertNotNull(links["prev"])
        assertNotNull(links["next"])
    }

    @Test
    fun `pagination links carry offset and size`() {
        val links = page(entryCount = 4, offset = 4, size = 4, totalItems = 12).links

        assertEquals("$baseUrl/$resourceUri?offset=4&size=4", links["self"]?.single()?.href)
        assertEquals("$baseUrl/$resourceUri?offset=0&size=4", links["prev"]?.single()?.href)
        assertEquals("$baseUrl/$resourceUri?offset=8&size=4", links["next"]?.single()?.href)
    }

    @Test
    fun `wire contract keeps snake case total_items, computed size and _embedded entries`() {
        val json = mapper.readTree(mapper.writeValueAsString(page(4, offset = 4, size = 4, totalItems = 12)))

        assertEquals(12, json.get("total_items").asInt())
        assertEquals(4, json.get("size").asInt())
        assertEquals(4, json.get("offset").asInt())
        assertEquals(4, json.get("_embedded").get("_entries").size())
        assertNotNull(json.get("_links").get("self"))
    }

    @Test
    fun `total_items is never smaller than the number of entries`() {
        assertEquals(4, page(entryCount = 4, offset = 0, size = 4, totalItems = 0).totalItems)
    }

    @Test
    fun `size 0 returns an unpaged self link`() {
        val links = page(entryCount = 4, offset = 0, size = 0, totalItems = 4).links

        assertEquals(1, links.size)
        assertEquals("$baseUrl/$resourceUri", links["self"]?.single()?.href)
    }
}
