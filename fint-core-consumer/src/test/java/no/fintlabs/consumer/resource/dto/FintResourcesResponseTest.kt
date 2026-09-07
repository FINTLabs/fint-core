package no.fintlabs.consumer.resource.dto

import no.novari.core.shared.json.FintJson
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FintResourcesResponseTest {
    private val baseUrl = "https://test.felleskomponent.no"
    private val resourceUri = "utdanning/elev/elev"
    private val mapper = FintJson.responseMapper(baseUrl)

    private fun page(
        entryCount: Int,
        offset: Long,
        size: Int,
        totalItems: Int,
        sinceTimeStamp: Long = 0,
    ) = createFintResourcesResponse(
        baseUrl,
        resourceUri,
        (1..entryCount).map { mapOf("n" to it) },
        offset,
        size,
        totalItems,
        sinceTimeStamp,
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
    fun `response contract keeps snake case total_items, computed size and _embedded entries`() {
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

    @Test
    fun `pagination links keep sinceTimeStamp`() {
        val links = page(entryCount = 5, offset = 5, size = 5, totalItems = 12, sinceTimeStamp = 1000).links

        assertEquals("$baseUrl/$resourceUri?sinceTimeStamp=1000&offset=5&size=5", links["self"]?.single()?.href)
        assertEquals("$baseUrl/$resourceUri?sinceTimeStamp=1000&offset=0&size=5", links["prev"]?.single()?.href)
        assertEquals("$baseUrl/$resourceUri?sinceTimeStamp=1000&offset=10&size=5", links["next"]?.single()?.href)
    }

    @Test
    fun `no next link when everything since the timestamp fits on one page`() {
        val links = page(entryCount = 3, offset = 0, size = 10, totalItems = 3, sinceTimeStamp = 1000).links

        assertEquals(setOf("self"), links.keys)
    }

    @Test
    fun `size 0 with sinceTimeStamp gives an unpaged self link that keeps the timestamp`() {
        val links = page(entryCount = 3, offset = 0, size = 0, totalItems = 3, sinceTimeStamp = 1000).links

        assertEquals(setOf("self"), links.keys)
        assertEquals("$baseUrl/$resourceUri?sinceTimeStamp=1000", links["self"]?.single()?.href)
    }

    @Test
    fun `links without sinceTimeStamp are unchanged`() {
        val links = page(entryCount = 10, offset = 0, size = 10, totalItems = 100).links

        assertEquals(setOf("self", "next"), links.keys)
        assertEquals("$baseUrl/$resourceUri?offset=0&size=10", links["self"]?.single()?.href)
        assertEquals("$baseUrl/$resourceUri?offset=10&size=10", links["next"]?.single()?.href)
    }

    @Test
    fun `a negative size is unpaged`() {
        val links = page(entryCount = 3, offset = 7, size = -1, totalItems = 3).links

        assertEquals("$baseUrl/$resourceUri", links["self"]?.single()?.href)
    }
}
