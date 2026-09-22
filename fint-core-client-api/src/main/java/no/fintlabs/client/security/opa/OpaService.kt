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
    ): OpaResult =
        if (opaProperties.enabled) {
            val opaRequest = createOpaRequest(principal, request, domainName, packageName, resourceName)
            opaClient.getDecision(opaRequest).result
        } else {
            OpaResult(allow = true)
        }
}
