package no.fintlabs.client.security

import no.fintlabs.client.resource.dto.FintResourcesResponse
import no.novari.fint.core.model.FintResource
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Applies [OpaFieldPruner] to a response body a controller returned as a value: a single
 * resource or a [FintResourcesResponse] page. A streamed dump never comes through here, its
 * writer prunes each resource itself.
 *
 * The body is inspected at runtime rather than by the controller's declared return type, because
 * the controller returns `ResponseEntity<FintResource>`, `ResponseEntity<FintResourcesResponse>`
 * and `ResponseEntity<Any>`, and the declared type says nothing about what is inside. Anything that
 * is not a resource or a list of resources is passed through untouched.
 */
@ControllerAdvice
class OpaFieldAdvice(
    private val jsonMapper: JsonMapper,
    private val pruner: OpaFieldPruner,
) : ResponseBodyAdvice<Any> {
    /**
     * Spring will only ever call [beforeBodyWrite] if this returns true
     */
    override fun supports(
        returnType: MethodParameter,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean = pruner.enabled

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Any? {
        if (body !is FintResource && body !is FintResourcesResponse) return body
        val tree = jsonMapper.valueToTree<ObjectNode>(body)
        val allowed = pruner.allowedFor((request as ServletServerHttpRequest).servletRequest)
        if (body is FintResourcesResponse) pruner.pruneEntries(tree, allowed) else pruner.pruneResource(tree, allowed)
        return tree
    }
}
