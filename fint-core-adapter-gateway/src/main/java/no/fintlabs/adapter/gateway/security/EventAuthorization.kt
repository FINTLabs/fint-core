package no.fintlabs.adapter.gateway.security

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.novari.core.shared.event.EventScope
import no.novari.core.shared.model.OrgId
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/**
 * The rules for reading and answering events, built on the adapter rules in
 * [AdapterAuthorization]. They throw rather than return false, because the controller needs
 * the allowed orgs and scopes back, not a yes or no.
 */
@Component
class EventAuthorization(
    private val adapterAuthorization: AdapterAuthorization,
) {
    /**
     * The scopes an adapter may read under [domainName]. A package it asks for by name must be
     * one it holds the role for. A domain-only request expands to one scope per package it
     * holds a role for in that domain, so the adapter is never handed an event it would be
     * refused to answer. Both failures are denied rather than answered with an empty list, so
     * an adapter that is set up wrong learns why.
     */
    fun readableScopes(
        authentication: Authentication,
        domainName: String,
        packageName: String?,
        resourceName: String?,
    ): List<EventScope> {
        if (packageName != null) {
            if (!adapterAuthorization.hasComponent(authentication, domainName, packageName)) {
                denied(DenialReason.MISSING_COMPONENT_ROLE)
            }
            return listOf(EventScope.of(domainName, packageName, resourceName))
        }

        val packages = adapterAuthorization.componentsIn(authentication, domainName)
        if (packages.isEmpty()) denied(DenialReason.MISSING_COMPONENT_ROLE)
        return packages.map { EventScope.of(domainName, it, null) }
    }

    /**
     * The orgs an adapter may read events for: the JWT assets this gateway serves that the
     * adapter has registered a contract for. An org without a contract is left out quietly,
     * because one missing sub-org should not block the others. No org at all is denied with a
     * reason, since an empty list would hide that the adapter still has to register. A token
     * with no assets never gets this far, because CorePrincipal refuses to be built from one.
     */
    fun readableOrgs(authentication: Authentication): List<OrgId> {
        val served = adapterAuthorization.servedOrgs(authentication)
        if (served.isEmpty()) denied(DenialReason.ORG_NOT_SERVED)

        val withContract = served.filter { adapterAuthorization.hasContract(authentication, it) }
        if (withContract.isEmpty()) denied(DenialReason.NO_REGISTERED_CONTRACT)
        return withContract
    }

    /**
     * Throws [AccessDeniedException] unless the answering adapter holds the component role for
     * the domain and package of [request].
     *
     * A [no.fintlabs.adapter.models.event.ResponseFintEvent] carries only a corrId and an orgId,
     * so the resource being written is known only from the request the consumer stored. That is
     * also the side worth trusting, because the adapter cannot choose it. The check therefore
     * runs after the stored request has been read, not in a `@PreAuthorize` on the controller.
     * The security context is read here rather than passed in, so the event service stays free
     * of authentication types.
     */
    fun requireRoleFor(request: RequestFintEvent) {
        val authentication =
            SecurityContextHolder.getContext().authentication
                ?: throw AccessDeniedException("Event answer has no authentication")

        if (!adapterAuthorization.hasComponent(authentication, request.domainName, request.packageName)) {
            denied(DenialReason.MISSING_COMPONENT_ROLE)
        }
    }

    private fun denied(reason: DenialReason): Nothing {
        DenialRecorder.record(reason)
        throw AccessDeniedException(reason.detail)
    }
}
