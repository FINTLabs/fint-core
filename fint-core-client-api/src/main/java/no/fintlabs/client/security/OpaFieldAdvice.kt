package no.fintlabs.client.security

import no.fintlabs.client.resource.dto.FintResourcesResponse
import no.fintlabs.client.security.opa.OpaDecision
import no.fintlabs.client.security.opa.OpaProperties
import no.novari.fint.core.model.FintResource
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Removes any field or relation from the response that OPA did not allow. What OPA allowed is
 * read from the request attribute [SecurityConfiguration.OPA_DECISION_ATTRIBUTE], which the
 * authorization check saves there. A resource that reaches this advice without a saved decision
 * gets nothing, not everything.
 *
 * The body is inspected at runtime rather than by the controller's declared return type, because
 * the controller returns `ResponseEntity<FintResource>`, `ResponseEntity<FintResourcesResponse>`
 * and `ResponseEntity<Any>`, and the declared type says nothing about what is inside. Anything that
 * is not a resource or a list of resources is passed through untouched.
 *
 * Names are compared ignoring case. The allowed names are lowercased when the decision is made,
 * while the JSON keys keep the model's spelling, for example `systemId`.
 *
 * A self link is an id field's value written as an href, so it follows that field's permission:
 * each self href is kept only when the id field it was built from is allowed. The id field is read
 * from the href itself, which `Link.href` always lays out as `.../<idField>/<value>`. An href whose
 * id field cannot be read is dropped rather than shown.
 */
@ControllerAdvice
class OpaFieldAdvice(
    private val jsonMapper: JsonMapper,
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
        if (body !is FintResource && body !is FintResourcesResponse) return body
        val tree = jsonMapper.valueToTree<ObjectNode>(body)
        val allowed = allowedFor(request)
        if (body is FintResourcesResponse) pruneEntries(tree, allowed) else pruneResource(tree, allowed)
        return tree
    }

    private fun allowedFor(request: ServerHttpRequest): OpaDecision.Allowed {
        val servletRequest = (request as ServletServerHttpRequest).servletRequest
        return servletRequest.getAttribute(SecurityConfiguration.OPA_DECISION_ATTRIBUTE) as? OpaDecision.Allowed
            ?: OpaDecision.Allowed(emptySet(), emptySet())
    }

    private fun pruneEntries(
        root: ObjectNode,
        allowed: OpaDecision.Allowed,
    ) {
        val entries = (root.get("_embedded") as? ObjectNode)?.get("_entries") as? ArrayNode ?: return
        entries.forEach { entry -> (entry as? ObjectNode)?.let { pruneResource(it, allowed) } }
    }

    private fun pruneResource(
        node: ObjectNode,
        allowed: OpaDecision.Allowed,
    ) {
        node.retain(node.propertyNames().filter { it == LINKS || it.lowercase() in allowed.fields })
        val links = node.get(LINKS) as? ObjectNode ?: return
        links.retain(links.propertyNames().filter { it == FintResource.SELF || it.lowercase() in allowed.relations })
        pruneSelfLinks(links, allowed.fields)
    }

    private fun pruneSelfLinks(
        links: ObjectNode,
        fields: Set<String>,
    ) {
        val self = links.get(FintResource.SELF) as? ArrayNode ?: return
        val allowed = self.filter { href -> idFieldOf(href).let { it != null && it in fields } }
        if (allowed.isEmpty()) {
            links.remove(FintResource.SELF)
        } else {
            links.putArray(FintResource.SELF).addAll(allowed)
        }
    }

    private fun idFieldOf(link: JsonNode): String? {
        val segments = link.get("href")?.asString()?.split('/') ?: return null
        return segments.getOrNull(segments.size - 2)?.lowercase()
    }

    companion object {
        private const val LINKS = "_links"
    }
}
