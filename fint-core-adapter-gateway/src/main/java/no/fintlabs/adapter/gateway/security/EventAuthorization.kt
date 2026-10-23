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
 * Which orgs an adapter reads events for, and whether it may answer one. Events need no
 * component role or contract; a contract only narrows a listed resource and opens READ.
 */
@Component
class EventAuthorization(
    private val adapterAuthorization: AdapterAuthorization,
) {
    /**
     * Every org in the token that this gateway serves, with the event capabilities of its
     * contract, or [EventCapabilities.NONE] for an org without one. Denied when no org is served
     * here.
     */
    fun readableOrgs(authentication: Authentication): Map<OrgId, EventCapabilities> {
        val served = adapterAuthorization.servedOrgs(authentication)
        if (served.isEmpty()) denied(DenialReason.ORG_NOT_SERVED)

        return served.associateWith { adapterAuthorization.eventCapabilitiesFor(authentication, it) }
    }

    /**
     * Throws [AccessDeniedException] when the adapter may not answer [request]: its contract lists
     * the resource without this operation, or the event is a READ the contract does not list.
     * Checked against the stored request, since the answer does not say which resource it is for.
     * Reads the security context itself, so the event service stays free of authentication types.
     */
    fun requireAnswerAllowed(request: RequestFintEvent) {
        val capabilities = adapterAuthorization.eventCapabilitiesFor(authentication(), OrgId.from(request.orgId))

        if (!capabilities.covers(request.toResourceRef(), request.operationType)) {
            denied(DenialReason.EVENT_NOT_IN_CONTRACT)
        }
    }

    /** The orgs of the answering adapter that this gateway serves. */
    fun answerableOrgs(): List<OrgId> = adapterAuthorization.servedOrgs(authentication())

    fun denyAnswerForOtherOrg(): Nothing = denied(DenialReason.EVENT_ORG_MISMATCH)

    private fun authentication(): Authentication =
        SecurityContextHolder.getContext().authentication
            ?: throw AccessDeniedException("Event answer has no authentication")

    private fun denied(reason: DenialReason): Nothing {
        DenialRecorder.record(reason)
        throw AccessDeniedException(reason.detail)
    }
}
