package no.fintlabs.client.config

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test

class FintPathRewriteFilterTest {
    private val filter = FintPathRewriteFilter()
    private val response = mockk<HttpServletResponse>()
    private val filterChain = mockk<FilterChain>(relaxed = true)

    @Test
    fun `forwards a felles kodeverk iso resource to its identity path`() {
        val dispatcher = mockk<RequestDispatcher>(relaxed = true)
        val request = requestFor("/felles/kodeverk/iso/landkode")
        every { request.getRequestDispatcher("/felles/kodeverk/landkode") } returns dispatcher

        filter.doFilterInternal(request, response, filterChain)

        verify { dispatcher.forward(request, response) }
    }

    @Test
    fun `keeps a suffix after the resource name when rewriting`() {
        val dispatcher = mockk<RequestDispatcher>(relaxed = true)
        val request = requestFor("/felles/kodeverk/iso/landkode/last-updated")
        every { request.getRequestDispatcher("/felles/kodeverk/landkode/last-updated") } returns dispatcher

        filter.doFilterInternal(request, response, filterChain)

        verify { dispatcher.forward(request, response) }
    }

    @Test
    fun `keeps a by-id suffix when rewriting`() {
        val dispatcher = mockk<RequestDispatcher>(relaxed = true)
        val request = requestFor("/felles/kodeverk/iso/landkode/systemid/no123")
        every { request.getRequestDispatcher("/felles/kodeverk/landkode/systemid/no123") } returns dispatcher

        filter.doFilterInternal(request, response, filterChain)

        verify { dispatcher.forward(request, response) }
    }

    @Test
    fun `passes through an ordinary three-segment resource path unchanged`() {
        val request = requestFor("/utdanning/vurdering/karakterverdi")

        filter.doFilterInternal(request, response, filterChain)

        verify { filterChain.doFilter(request, response) }
    }

    @Test
    fun `passes through a package overview path unchanged`() {
        val request = requestFor("/felles/kodeverk")

        filter.doFilterInternal(request, response, filterChain)

        verify { filterChain.doFilter(request, response) }
    }

    @Test
    fun `passes through the bare extra segment with nothing after it unchanged`() {
        val request = requestFor("/felles/kodeverk/iso")

        filter.doFilterInternal(request, response, filterChain)

        verify { filterChain.doFilter(request, response) }
    }

    @Test
    fun `passes through an unrelated three-segment prefix that happens to be long enough`() {
        val request = requestFor("/utdanning/vurdering/karakterverdi/systemid/no123")

        filter.doFilterInternal(request, response, filterChain)

        verify { filterChain.doFilter(request, response) }
    }

    private fun requestFor(uri: String): HttpServletRequest =
        mockk {
            every { requestURI } returns uri
            every { contextPath } returns ""
        }
}
