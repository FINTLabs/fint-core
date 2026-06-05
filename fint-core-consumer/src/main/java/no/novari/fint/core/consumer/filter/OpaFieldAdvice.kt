package no.novari.fint.core.consumer.filter

import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider
import jakarta.servlet.http.HttpServletRequest
import no.novari.fint.core.consumer.security.CoreAccessService.Companion.OPA_FIELDS_ATTRIBUTE
import no.novari.fint.core.consumer.security.CoreAccessService.Companion.OPA_RELATIONS_ATTRIBUTE
import no.novari.fint.core.consumer.security.opa.OpaProperties
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJacksonValue
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

/**
 * Prunes response bodies to the fields/relations OPA allowed for the caller. The allowed sets are
 * stashed on the request by [no.novari.fint.core.consumer.security.CoreAccessService] during authorization;
 * here they drive a Jackson [OpaFilter]. Only active when OPA is enabled; otherwise the body is
 * passed through untouched. This is the canonical MVC mechanism (wrapping in [MappingJacksonValue]),
 * replacing the previous reactive `ResponseBodyResultHandler` subclass.
 */
@ControllerAdvice
class OpaFieldAdvice(
    private val opaProperties: OpaProperties,
) : ResponseBodyAdvice<Any> {
    override fun supports(
        returnType: MethodParameter,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean = opaProperties.enabled

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Any? {
        if (body == null) return null
        val servletRequest = (request as ServletServerHttpRequest).servletRequest
        val opaFilter =
            OpaFilter(
                attribute(servletRequest, OPA_FIELDS_ATTRIBUTE),
                attribute(servletRequest, OPA_RELATIONS_ATTRIBUTE),
            )
        return MappingJacksonValue(body).apply {
            filters = SimpleFilterProvider().addFilter("opaFilter", opaFilter).setFailOnUnknownId(false)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun attribute(
        request: HttpServletRequest,
        key: String,
    ): Set<String> = (request.getAttribute(key) as? Set<String>) ?: emptySet()
}
