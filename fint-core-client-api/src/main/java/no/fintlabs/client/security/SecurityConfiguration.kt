package no.fintlabs.client.security

import no.fintlabs.client.security.opa.OpaDecision
import no.fintlabs.client.security.opa.OpaService
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
    private val opaService: OpaService,
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
        AuthorizationManager { authentication, _ -> decide(authentication.get().clientDenial()) }

    private fun requireClientWithAccess(): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManager { authentication, context -> decide(authentication.get().accessDenial(context)) }

    private fun decide(denial: Denial?): AuthorizationDecision = denial?.let(::Denied) ?: AuthorizationDecision(true)

    private fun Authentication.clientDenial(): Denial? =
        when {
            this !is CorePrincipal -> Denial.NotAClient
            type != FintType.CLIENT -> Denial.WrongType
            FintScope.FINT_CLIENT !in scopes -> Denial.MissingScope
            else -> null
        }

    /**
     * Every way a resource request can be denied, in the order it is checked. Returns null when
     * the request is allowed. A missing org-id header is not a denial, it is a malformed request,
     * and `@RequestHeader` rejects it with 400 further down the chain.
     */
    private fun Authentication.accessDenial(context: RequestAuthorizationContext): Denial? {
        if (this !is CorePrincipal) return Denial.NotAClient
        clientDenial()?.let { return it }

        val domainName = context.variables["domainName"]
        val packageName = context.variables["packageName"]
        if (domainName == null || packageName == null || !hasComponent(domainName, packageName)) {
            return Denial.MissingComponentRole
        }

        val requestedOrgId = context.request.getHeader(ORG_ID_HEADER)
        if (requestedOrgId != null && assets.none { OrgId.from(it) == OrgId.from(requestedOrgId) }) {
            return Denial.OrgNotInAssets(requestedOrgId)
        }
        return opaDenial(context, domainName, packageName)
    }

    /** Saves what OPA allowed so [OpaFieldAdvice] can prune the response later. */
    private fun CorePrincipal.opaDenial(
        context: RequestAuthorizationContext,
        domainName: String,
        packageName: String,
    ): Denial? {
        val resourceName = context.variables["resourceName"]
        return when (
            val decision =
                opaService.requestDecision(
                    this,
                    context.request,
                    domainName,
                    packageName,
                    resourceName,
                )
        ) {
            is OpaDecision.Allowed -> {
                context.request.setAttribute(OPA_FIELDS_ATTRIBUTE, decision.fields)
                context.request.setAttribute(OPA_RELATIONS_ATTRIBUTE, decision.relations)
                null
            }

            OpaDecision.Denied -> {
                Denial.ResourceNotGranted
            }

            OpaDecision.Unavailable -> {
                Denial.AccessControlUnavailable
            }
        }
    }

    companion object {
        const val OPA_FIELDS_ATTRIBUTE = "x-opa-fields"
        const val OPA_RELATIONS_ATTRIBUTE = "x-opa-relations"
        private const val ORG_ID_HEADER = "x-org-id"
        private const val RESOURCE_PATH = "/{domainName}/{packageName}/{resourceName}/**"
        private const val ENDPOINTS_PATH = "/{domainName}/{packageName}"
        private val OPEN_PATHS =
            arrayOf(
                "/swagger-ui/**",
                "/swagger-ui.html",
                "/v3/api-docs/**",
                "/actuator/health",
            )
    }
}
