package no.fintlabs.adapter.gateway.security

import no.fintlabs.adapter.models.event.RequestFintEvent
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/**
 * The role check for an event answer.
 *
 * A [no.fintlabs.adapter.models.event.ResponseFintEvent] carries only a corrId and an orgId, so
 * the resource being written is known only from the request the consumer stored. That is also
 * the side worth trusting, because the adapter cannot choose it. The check therefore runs after
 * the stored request has been read, not in a `@PreAuthorize` on the controller.
 *
 * The security context is read here rather than passed in, so the event service stays free of
 * authentication types.
 */
@Component
class EventAnswerAuthorization(
    private val adapterAuthorization: AdapterAuthorization,
) {
    /**
     * Throws [AccessDeniedException] unless the answering adapter holds the component role for
     * the domain and package of [request].
     */
    fun requireRoleFor(request: RequestFintEvent) {
        val authentication =
            SecurityContextHolder.getContext().authentication
                ?: throw AccessDeniedException("Event answer has no authentication")

        if (!adapterAuthorization.hasComponent(authentication, request.domainName, request.packageName)) {
            throw AccessDeniedException(
                "Adapter is missing the role for ${request.domainName}/${request.packageName}",
            )
        }
    }
}
