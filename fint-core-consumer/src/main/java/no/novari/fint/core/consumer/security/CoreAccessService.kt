package no.novari.fint.core.consumer.security

import jakarta.servlet.http.HttpServletRequest
import no.novari.fint.core.consumer.security.opa.OpaService
import no.novari.resource.server.authentication.CorePrincipal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Servlet port of the previous reactive `CoreAccessService`. Authorises a [CorePrincipal] by
 * checking FINT type, scope and component access, then OPA. The OPA call also yields the allowed
 * fields/relations, stashed as request attributes for [no.novari.fint.core.consumer.filter.OpaFieldAdvice]
 * to prune the response with. Behaviour matches the previous reactive implementation, including
 * fail-closed on a missing/denying OPA decision.
 */
@Service
class CoreAccessService(
    private val securityProperties: SecurityProperties,
    private val opaService: OpaService,
) {
    fun authorize(
        principal: CorePrincipal,
        request: HttpServletRequest,
    ): Boolean =
        when {
            !matchesType(principal) -> deny("type", principal)
            !matchesScope(principal) -> deny("scope", principal)
            !matchesComponent(principal, request) -> deny("component", principal)
            else -> checkOpa(principal, request)
        }

    private fun matchesType(principal: CorePrincipal): Boolean =
        securityProperties.fintType?.let { it == principal.type } ?: true

    private fun matchesScope(principal: CorePrincipal): Boolean =
        securityProperties.requiredScopes?.any { it in principal.scopes } ?: true

    private fun matchesComponent(
        principal: CorePrincipal,
        request: HttpServletRequest,
    ): Boolean {
        val segments =
            request.requestURI
                .removePrefix(request.contextPath)
                .split('/')
                .filter(String::isNotBlank)
        val domain = segments.getOrNull(0) ?: return false
        val pkg = segments.getOrNull(1) ?: return false
        return principal.hasComponent(domain, pkg)
    }

    private fun checkOpa(
        principal: CorePrincipal,
        request: HttpServletRequest,
    ): Boolean {
        val opa = opaService.requestOpa(principal.token, request)
        request.setAttribute(OPA_FIELDS_ATTRIBUTE, opa.result.fields)
        request.setAttribute(OPA_RELATIONS_ATTRIBUTE, opa.result.relations)
        return opa.result.allow
    }

    private fun deny(
        reason: String,
        principal: CorePrincipal,
    ): Boolean {
        logger.debug("Authorization failed on {} for principal {}", reason, principal.username)
        return false
    }

    companion object {
        const val OPA_FIELDS_ATTRIBUTE = "x-opa-fields"
        const val OPA_RELATIONS_ATTRIBUTE = "x-opa-relations"
        private val logger = LoggerFactory.getLogger(CoreAccessService::class.java)
    }
}
