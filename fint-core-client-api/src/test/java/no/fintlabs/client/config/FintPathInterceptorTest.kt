package no.fintlabs.client.config

import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import no.fintlabs.client.exception.resource.ResourceNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.HandlerMapping

class FintPathInterceptorTest {
    private val interceptor = FintPathInterceptor()

    @Test
    fun `passes through a request with no path variables`() {
        val request = requestWithVariables(null)

        assertThat(interceptor.preHandle(request, mockk(), mockk())).isTrue()
    }

    @Test
    fun `passes through a request with no packageName`() {
        val request = requestWithVariables(mapOf("domainName" to "utdanning"))

        assertThat(interceptor.preHandle(request, mockk(), mockk())).isTrue()
    }

    @Test
    fun `passes through a valid component`() {
        val request = requestWithVariables(mapOf("domainName" to "utdanning", "packageName" to "vurdering"))

        assertThat(interceptor.preHandle(request, mockk(), mockk())).isTrue()
    }

    @Test
    fun `rejects an unknown domain at component level`() {
        val request = requestWithVariables(mapOf("domainName" to "nonsense", "packageName" to "vurdering"))

        assertThatThrownBy { interceptor.preHandle(request, mockk(), mockk()) }
            .isInstanceOf(ResourceNotFoundException::class.java)
    }

    @Test
    fun `rejects an unknown package at component level`() {
        val request = requestWithVariables(mapOf("domainName" to "utdanning", "packageName" to "nonsense"))

        assertThatThrownBy { interceptor.preHandle(request, mockk(), mockk()) }
            .isInstanceOf(ResourceNotFoundException::class.java)
    }

    @Test
    fun `passes through a valid resource`() {
        val request =
            requestWithVariables(
                mapOf("domainName" to "utdanning", "packageName" to "vurdering", "resourceName" to "karakterverdi"),
            )

        assertThat(interceptor.preHandle(request, mockk(), mockk())).isTrue()
    }

    @Test
    fun `rejects an unknown resource`() {
        val request =
            requestWithVariables(
                mapOf("domainName" to "utdanning", "packageName" to "vurdering", "resourceName" to "nonsense"),
            )

        assertThatThrownBy { interceptor.preHandle(request, mockk(), mockk()) }
            .isInstanceOf(ResourceNotFoundException::class.java)
    }

    private fun requestWithVariables(variables: Map<String, String>?) =
        mockk<HttpServletRequest> {
            every { getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) } returns variables
        }
}
