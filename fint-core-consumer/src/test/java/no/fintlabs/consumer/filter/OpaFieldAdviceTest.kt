package no.fintlabs.consumer.filter

import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import no.fintlabs.consumer.security.CoreAccessService.Companion.OPA_FIELDS_ATTRIBUTE
import no.fintlabs.consumer.security.CoreAccessService.Companion.OPA_RELATIONS_ATTRIBUTE
import no.fintlabs.consumer.security.opa.OpaProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.http.converter.json.MappingJacksonValue
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest

class OpaFieldAdviceTest {
    private val converterType = MappingJackson2HttpMessageConverter::class.java

    private fun advice(enabled: Boolean) = OpaFieldAdvice(OpaProperties().apply { this.enabled = enabled })

    @Test
    fun `supports reflects the opa enabled flag`() {
        val returnType = mockk<MethodParameter>(relaxed = true)
        assertTrue(advice(enabled = true).supports(returnType, converterType))
        assertFalse(advice(enabled = false).supports(returnType, converterType))
    }

    @Test
    fun `beforeBodyWrite wraps the body in a filtered MappingJacksonValue`() {
        val servletRequest =
            mockk<HttpServletRequest> {
                every { getAttribute(OPA_FIELDS_ATTRIBUTE) } returns setOf("systemid")
                every { getAttribute(OPA_RELATIONS_ATTRIBUTE) } returns null
            }
        val request = mockk<ServletServerHttpRequest> { every { this@mockk.servletRequest } returns servletRequest }

        val body = mapOf("a" to 1)
        val result =
            advice(enabled = true).beforeBodyWrite(
                body,
                mockk(relaxed = true),
                MediaType.APPLICATION_JSON,
                converterType,
                request,
                mockk<ServerHttpResponse>(relaxed = true),
            )

        assertInstanceOf(MappingJacksonValue::class.java, result)
        result as MappingJacksonValue
        assertEquals(body, result.value)
        assertNotNull(result.filters)
    }

    @Test
    fun `beforeBodyWrite passes through a null body`() {
        val result =
            advice(enabled = true).beforeBodyWrite(
                null,
                mockk(relaxed = true),
                MediaType.APPLICATION_JSON,
                converterType,
                mockk(relaxed = true),
                mockk(relaxed = true),
            )
        assertNull(result)
    }
}
