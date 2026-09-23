package no.fintlabs.client.security.opa

import jakarta.servlet.http.HttpServletRequest
import no.novari.resource.server.authentication.CorePrincipal
import org.springframework.stereotype.Service

@Service
class OpaService(
    private val opaProperties: OpaProperties,
    private val opaClient: OpaClient,
) {
    fun requestDecision(
        principal: CorePrincipal,
        request: HttpServletRequest,
        domainName: String,
        packageName: String,
        resourceName: String?,
    ): OpaDecision =
        if (opaProperties.enabled) {
            opaClient.getDecision(createOpaRequest(principal, request, domainName, packageName, resourceName))
        } else {
            OpaDecision.Allowed(emptySet(), emptySet())
        }
}
