package no.novari.fint.core.consumer.resource

import io.mockk.every
import io.mockk.mockk
import no.novari.fint.core.shared.cache.CacheService
import no.novari.fint.core.shared.cache.FintCache
import no.novari.fint.core.shared.link.LinkService
import no.novari.fint.core.shared.resource.FintResources
import no.novari.fint.model.resource.FintResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ResourceServiceTest {
    private val linkService = mockk<LinkService>()
    private val cacheService = mockk<CacheService>()
    private val resourceService = ResourceService(linkService, cacheService)

    @Test
    fun `getResources fetches from cache and transforms through linkService`() {
        val cache = mockk<FintCache>()
        val resources = listOf(mockk<FintResource>())
        val expected = mockk<FintResources>()

        every { cacheService.getCache("employee") } returns cache
        every { cache.getList(10L, 0L, 0L, null) } returns resources
        every { cache.size } returns 100
        every { linkService.toResources("employee", resources, 0, 10, 100) } returns expected

        val result = resourceService.getResources("employee", 10, 0, 0L, null)

        assertEquals(expected, result)
    }
}
