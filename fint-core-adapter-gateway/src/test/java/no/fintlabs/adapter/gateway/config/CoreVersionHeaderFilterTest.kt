package no.fintlabs.adapter.gateway.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class CoreVersionHeaderFilterTest {
    private val filter = CoreVersionHeaderFilter()

    @Test
    fun `adds the core version header to the response`() {
        val response = MockHttpServletResponse()

        filter.doFilterInternal(MockHttpServletRequest(), response, MockFilterChain())

        assertEquals("2.1", response.getHeader("x-core-version"))
    }

    @Test
    fun `passes the request on to the rest of the chain`() {
        val request = MockHttpServletRequest()
        val chain = MockFilterChain()

        filter.doFilterInternal(request, MockHttpServletResponse(), chain)

        assertSame(request, chain.request)
    }
}
