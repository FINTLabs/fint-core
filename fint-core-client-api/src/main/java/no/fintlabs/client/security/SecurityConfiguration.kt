package no.fintlabs.client.security

import no.novari.core.shared.model.OrgId
import no.novari.resource.server.authentication.CorePrincipal
import no.novari.resource.server.converter.CorePrincipalConverter
import no.novari.resource.server.enums.FintScope
import no.novari.resource.server.enums.FintType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.RequestAuthorizationContext

@Configuration
@EnableWebSecurity
class SecurityConfiguration(
    private val securityProblemDetailHandler: SecurityProblemDetailHandler,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .authorizeHttpRequests { requests ->
                requests
                    .requestMatchers(*OPEN_PATHS)
                    .permitAll()
                    .requestMatchers(RESOURCE_PATH, ENDPOINTS_PATH)
                    .access(requireClientWithAccess())
                    .anyRequest()
                    .access(requireClient())
            }.oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt -> jwt.jwtAuthenticationConverter(CorePrincipalConverter()) }
                oauth2.authenticationEntryPoint(securityProblemDetailHandler)
                oauth2.accessDeniedHandler(securityProblemDetailHandler)
            }.exceptionHandling {
                it.authenticationEntryPoint(securityProblemDetailHandler)
                it.accessDeniedHandler(securityProblemDetailHandler)
            }.build()

    private fun requireClient(): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManager { authentication, _ ->
            AuthorizationDecision(authentication.get().isFintClient())
        }

    private fun requireClientWithAccess(): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManager { authentication, context ->
            AuthorizationDecision(authentication.get().canAccess(context))
        }

    private fun Authentication.isFintClient(): Boolean =
        this is CorePrincipal && type == FintType.CLIENT && FintScope.FINT_CLIENT in scopes

    /** A missing org header isn't denied here; `@RequestHeader` rejects it with 400 downstream. */
    private fun Authentication.canAccess(context: RequestAuthorizationContext): Boolean {
        if (this !is CorePrincipal || !isFintClient()) return false
        val domainName = context.variables["domainName"] ?: return false
        val packageName = context.variables["packageName"] ?: return false
        if (!hasComponent(domainName, packageName)) return false
        val requestedOrgId = context.request.getHeader(ORG_ID_HEADER) ?: return true
        return assets.any { OrgId.from(it) == OrgId.from(requestedOrgId) }
    }

    companion object {
        private const val ORG_ID_HEADER = "x-org-id"
        private const val RESOURCE_PATH = "/{domainName}/{packageName}/{resourceName}/**"
        private const val ENDPOINTS_PATH = "/{domainName}/{packageName}"

        /**
         * The two endpoints the platform calls without a token: the health probe and the Prometheus
         * scrape. The ingress only routes resource paths, so neither is reachable from outside the
         * cluster. Anything else under `/actuator` stays behind the client check.
         */
        private val OPEN_PATHS =
            arrayOf(
                "/actuator/health",
                "/actuator/prometheus",
            )
    }
}
