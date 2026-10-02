package no.fintlabs.adapter.gateway.security

import jakarta.servlet.http.HttpServletRequest
import no.fintlabs.adapter.gateway.config.ProviderProperties
import no.fintlabs.adapter.gateway.register.CapabilityKey
import no.fintlabs.adapter.gateway.register.ContractLookup
import no.fintlabs.adapter.gateway.register.ContractService
import no.novari.core.shared.model.OrgId
import no.novari.resource.server.authentication.CorePrincipal
import no.novari.resource.server.enums.FintScope
import no.novari.resource.server.enums.FintType
import org.springframework.security.core.Authentication
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.stereotype.Component

@Component("adapterAuth")
class AdapterAuthorization(
    private val providerProperties: ProviderProperties,
    private val contractService: ContractService,
) {
    fun isAdapter(
        authentication: Authentication,
        request: HttpServletRequest,
    ): Boolean = adapterOrNull(authentication, request) != null

    fun isAdapterOf(
        authentication: Authentication,
        request: HttpServletRequest,
        orgId: String,
    ): Boolean {
        val principal = adapterOrNull(authentication, request) ?: return false
        return principal.orgId == orgId
    }

    fun canAccessComponent(
        authentication: Authentication,
        context: RequestAuthorizationContext,
    ): Boolean {
        val principal = adapterOrNull(authentication, context.request) ?: return false
        val domainName =
            context.variables["domainName"] ?: return deny(DenialReason.MISSING_COMPONENT_ROLE, context.request)
        val packageName =
            context.variables["packageName"] ?: return deny(DenialReason.MISSING_COMPONENT_ROLE, context.request)

        return principal.hasComponent(domainName, packageName) ||
            deny(
                DenialReason.MISSING_COMPONENT_ROLE,
                context.request,
            )
    }

    /**
     * The org must be one the JWT carries and one this gateway serves. Both halves matter: the
     * assets list sub-orgs one by one, and this deployment only owns its configured main org
     * and everything under it.
     */
    fun hasOrg(
        authentication: Authentication,
        orgId: String?,
    ): Boolean {
        val principal = adapterOrNull(authentication) ?: return false
        if (orgId.isNullOrBlank()) return deny(DenialReason.ORG_MISSING)

        val requested = OrgId.from(orgId)

        if (principal.assets.none { asset -> asOrgId(asset) == requested }) {
            return deny(DenialReason.ORG_NOT_IN_ASSETS)
        }
        if (!requested.belongsTo(providerProperties.orgId)) {
            return deny(DenialReason.ORG_NOT_SERVED)
        }
        return true
    }

    fun isUsername(
        authentication: Authentication,
        username: String?,
    ): Boolean {
        val principal = adapterOrNull(authentication) ?: return false
        return principal.username == username || deny(DenialReason.USERNAME_MISMATCH)
    }

    fun hasComponent(
        authentication: Authentication,
        domainName: String,
        packageName: String,
    ): Boolean {
        val principal = adapterOrNull(authentication) ?: return false
        return principal.hasComponent(domainName.trim().lowercase(), packageName.trim().lowercase()) ||
            deny(DenialReason.MISSING_COMPONENT_ROLE)
    }

    /**
     * The component role for the path is already required by the filter chain, so this adds
     * the two things only the body can answer: which org the page is for, and whether the
     * adapter's contract for that org covers this resource.
     */
    fun canSync(
        authentication: Authentication,
        orgId: String?,
        domainName: String,
        packageName: String,
        resourceName: String,
    ): Boolean {
        if (!hasOrg(authentication, orgId)) return false
        val principal = adapterOrNull(authentication) ?: return false

        return contractCovers(
            username = principal.username,
            orgId = orgId!!,
            capability = CapabilityKey.of(domainName, packageName, resourceName),
        )
    }

    /**
     * Answering an event does not require the resource to be in the contract, because a
     * contract describes what an adapter delivers on sync, not what it accepts writes for. It
     * does require a contract for the org, so the registry stays a true picture of who is
     * serving that org.
     */
    fun canAnswerFor(
        authentication: Authentication,
        orgId: String?,
    ): Boolean {
        if (!hasOrg(authentication, orgId)) return false
        val principal = adapterOrNull(authentication) ?: return false

        return when (contractService.lookup(principal.username, orgId!!)) {
            is ContractLookup.Found -> true
            ContractLookup.Absent -> deny(DenialReason.NO_CONTRACT)
        }
    }

    /**
     * The packages under [domainName] the adapter holds a component role for, sorted.
     */
    fun componentsIn(
        authentication: Authentication,
        domainName: String,
    ): List<String> {
        val principal = adapterOrNull(authentication) ?: return emptyList()
        val prefix = "${domainName.trim().lowercase()}_"
        return principal.components
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .sorted()
    }

    /**
     * The JWT assets that this gateway serves. Assets for another main org are dropped, so a
     * gateway never reads or writes another org's data.
     */
    fun servedOrgs(authentication: Authentication): List<OrgId> {
        val principal = adapterOrNull(authentication) ?: return emptyList()
        return principal.assets.mapNotNull(::asOrgId).filter { it.belongsTo(providerProperties.orgId) }
    }

    fun hasContract(
        authentication: Authentication,
        orgId: OrgId,
    ): Boolean {
        val principal = adapterOrNull(authentication) ?: return false
        return contractService.lookup(principal.username, orgId.value) is ContractLookup.Found
    }

    private fun contractCovers(
        username: String,
        orgId: String,
        capability: CapabilityKey,
    ): Boolean =
        when (val lookup = contractService.lookup(username, orgId)) {
            is ContractLookup.Found -> capability in lookup.capabilities || deny(DenialReason.RESOURCE_NOT_IN_CONTRACT)
            ContractLookup.Absent -> deny(DenialReason.NO_CONTRACT)
        }

    private fun adapterOrNull(
        authentication: Authentication?,
        request: HttpServletRequest? = null,
    ): CorePrincipal? {
        val principal = authentication as? CorePrincipal ?: return denyNull(DenialReason.NOT_ADAPTER, request)
        if (principal.type != FintType.ADAPTER) return denyNull(DenialReason.NOT_ADAPTER, request)
        if (FintScope.FINT_ADAPTER !in principal.scopes) return denyNull(DenialReason.MISSING_SCOPE, request)
        return principal
    }

    private fun asOrgId(rawValue: String): OrgId? = runCatching { OrgId.from(rawValue) }.getOrNull()

    private fun denyNull(
        reason: DenialReason,
        request: HttpServletRequest?,
    ): CorePrincipal? {
        deny(reason, request)
        return null
    }

    private fun deny(
        reason: DenialReason,
        request: HttpServletRequest? = null,
    ): Boolean {
        if (request != null) DenialRecorder.record(request, reason) else DenialRecorder.record(reason)
        return false
    }
}
