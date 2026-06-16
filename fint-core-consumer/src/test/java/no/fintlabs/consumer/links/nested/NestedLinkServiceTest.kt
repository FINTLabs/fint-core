package no.fintlabs.consumer.links.nested

import io.mockk.every
import io.mockk.mockk
import no.fintlabs.consumer.config.ConsumerConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NestedLinkServiceTest {
    private lateinit var nestedLinkService: NestedLinkService

    @BeforeEach
    fun setUp() {
        val nestedLinkMapper = mockk<NestedLinkMapper>(relaxed = true)
        val configuration = mockk<ConsumerConfiguration>(relaxed = true)
        every { nestedLinkMapper.packageToUriMap } returns mapOf("foo" to "foo/test")
        every { configuration.baseUrl } returns "https://bar.no"
        nestedLinkService = NestedLinkService(configuration, nestedLinkMapper, null)
    }

    @Test
    fun `getLink appends base URL and replaces ${} package references`() {
        assertEquals("https://bar.no/foo/test/abc", nestedLinkService.getLink("\${foo}/abc"))
    }

    @Test
    fun `getLink appends base URL if link starts with slash`() {
        assertEquals("https://bar.no/foo/test/abc", nestedLinkService.getLink("/foo/test/abc"))
    }
}
