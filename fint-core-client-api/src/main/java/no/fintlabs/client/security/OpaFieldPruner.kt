package no.fintlabs.client.security

import jakarta.servlet.http.HttpServletRequest
import no.fintlabs.client.security.opa.OpaDecision
import no.fintlabs.client.security.opa.OpaProperties
import no.novari.fint.core.model.FintResource
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Removes any field or relation from a resource that OPA did not allow. What OPA allowed is
 * read from the request attribute [SecurityConfiguration.OPA_DECISION_ATTRIBUTE], which the
 * authorization check saves there. A resource that is served without a saved decision gets
 * nothing, not everything.
 *
 * [OpaFieldAdvice] applies this to a response body after the controller has returned it, and
 * [no.fintlabs.client.resource.ResourceDumpWriter] applies it to each resource as it is written,
 * since a streamed body never passes through the advice.
 *
 * Names are compared ignoring case. The allowed names are lowercased when the decision is made,
 * while the JSON keys keep the model's spelling, for example `systemId`.
 *
 * A self link is an id field's value written as an href, so it follows that field's permission:
 * each self href is kept only when the id field it was built from is allowed. The id field is read
 * from the href itself, which `Link.href` always lays out as `.../<idField>/<value>`. An href whose
 * id field cannot be read is dropped rather than shown.
 */
@Component
class OpaFieldPruner(
    private val opaProperties: OpaProperties,
) {
    /** False means nothing is pruned, which is what [OpaFieldAdvice] also goes by. */
    val enabled: Boolean get() = opaProperties.enabled

    fun allowedFor(request: HttpServletRequest): OpaDecision.Allowed =
        request.getAttribute(SecurityConfiguration.OPA_DECISION_ATTRIBUTE) as? OpaDecision.Allowed
            ?: OpaDecision.Allowed(emptySet(), emptySet())

    fun pruneEntries(
        root: ObjectNode,
        allowed: OpaDecision.Allowed,
    ) {
        val entries = (root.get("_embedded") as? ObjectNode)?.get("_entries") as? ArrayNode ?: return
        entries.forEach { entry -> (entry as? ObjectNode)?.let { pruneResource(it, allowed) } }
    }

    fun pruneResource(
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
