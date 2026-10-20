package no.fintlabs.adapter.gateway.security

import no.fintlabs.adapter.gateway.register.EventCapabilities
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.novari.core.shared.event.toResourceRef
import no.novari.core.shared.model.OrgId
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/**
 * The rules for reading and answering events, built on the adapter rules in
 * [AdapterAuthorization]. Events need neither a component role nor a contract, as on
 * provider-gateway: an adapter reads and answers the events of the orgs in its own token, and
 * the path only filters. A contract narrows what a listed resource gets and is the only way to
 * be served a READ. The rules throw rather than return false, because the controller needs the
 * orgs back, not a yes or no.
 */
@Component
class EventAuthorization(
    private val adapterAuthorization: AdapterAuthorization,
) {
    /**
     * The orgs an adapter reads events for, with what each is served: every JWT asset this
     * gateway serves, with the event capabilities of its registered contract, or
     * [EventCapabilities.NONE] for an org without one, which stands for every operation except
     * READ. A token whose orgs are all served elsewhere is denied with a reason rather than
     * answered with an empty list, so an adapter pointed at the wrong gateway learns why. A token
     * with no assets never gets this far, because CorePrincipal refuses to be built from one.
     */
    fun readableOrgs(authentication: Authentication): Map<OrgId, EventCapabilities> {
        val served = adapterAuthorization.servedOrgs(authentication)
        if (served.isEmpty()) denied(DenialReason.ORG_NOT_SERVED)

        return served.associateWith { adapterAuthorization.eventCapabilitiesFor(authentication, it) }
    }

    /**
     * Throws [AccessDeniedException] when what the adapter is served for the org of [request]
     * does not cover its resource and operation: a contract that lists the resource without the
     * operation, or a READ the contract does not list. Without a contract only a READ is refused.
     *
     * A [no.fintlabs.adapter.models.event.ResponseFintEvent] carries only a corrId and an orgId,
     * so the resource being answered is known only from the request the consumer stored. That is
     * also the side worth trusting, because the adapter cannot choose it. The check therefore
     * runs after the stored request has been read, not in a `@PreAuthorize` on the controller.
     * The security context is read here rather than passed in, so the event service stays free
     * of authentication types.
     */
    fun requireAnswerAllowed(request: RequestFintEvent) {
        val capabilities = adapterAuthorization.eventCapabilitiesFor(authentication(), OrgId.from(request.orgId))

        if (!capabilities.covers(request.toResourceRef(), request.operationType)) {
            denied(DenialReason.EVENT_NOT_IN_CONTRACT)
        }
    }

    /**
     * The orgs of the answering adapter that this gateway serves, read from the security context
     * like [requireAnswerAllowed].
     */
    fun answerableOrgs(): List<OrgId> = adapterAuthorization.servedOrgs(authentication())

    /**
     * Refuses an answer that names another of the adapter's orgs than the one the event belongs to.
     */
    fun denyAnswerForOtherOrg(): Nothing = denied(DenialReason.EVENT_ORG_MISMATCH)

    private fun authentication(): Authentication =
        SecurityContextHolder.getContext().authentication
            ?: throw AccessDeniedException("Event answer has no authentication")

    private fun denied(reason: DenialReason): Nothing {
        DenialRecorder.record(reason)
        throw AccessDeniedException(reason.detail)
    }
}
