package no.fintlabs.consumer.security.opa

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component

@Component
class OpaMapper(
    private val opaProperties: OpaProperties,
) {
    fun createOpaRequest(
        jwt: Jwt,
        request: HttpServletRequest,
    ): OpaRequest =
        OpaRequest(
            username = jwt.getClaimAsString("cn"),
            env = env(request),
            domainName = segment(request, 0) ?: error("Missing domain segment"),
            packageName = segment(request, 1) ?: error("Missing package segment"),
            resourceName = segment(request, 2),
        )

    private fun env(request: HttpServletRequest): String =
        if (opaProperties.envHeader) {
            request.getHeader("x-opa-env")?.takeIf(String::isNotBlank) ?: error("Missing X-Opa-Env header")
        } else {
            request.serverName.substringBefore('.')
        }

    private fun segment(
        request: HttpServletRequest,
        index: Int,
    ): String? =
        request.requestURI
            .removePrefix(request.contextPath)
            .split('/')
            .filter(String::isNotBlank)
            .getOrNull(index)
}
