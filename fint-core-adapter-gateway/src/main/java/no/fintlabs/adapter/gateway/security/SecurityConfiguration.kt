package no.fintlabs.adapter.gateway.security

import jakarta.servlet.DispatcherType
import no.fintlabs.adapter.gateway.ProviderApi
import no.fintlabs.adapter.gateway.admin.AdminController
import no.novari.resource.server.converter.CorePrincipalConverter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.RequestAuthorizationContext

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfiguration(
    private val securityProblemDetailHandler: SecurityProblemDetailHandler,
    private val adapterAuthorization: AdapterAuthorization,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .authorizeHttpRequests { requests ->
                requests
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, RELATION_EDGE_REBUILD_PATH)
                    .access(requireAdapterOf(RELATION_EDGE_ADMIN_ORG_ID))
                    .requestMatchers(HttpMethod.POST, RELATION_EDGE_DRIFT_PATH)
                    .access(requireAdapterOf(RELATION_EDGE_ADMIN_ORG_ID))
                    .requestMatchers(HttpMethod.GET, RELATION_EDGE_JOB_PATH)
                    .access(requireAdapterOf(RELATION_EDGE_ADMIN_ORG_ID))
                    .requestMatchers(ADMIN_PATHS)
                    .denyAll()
                    .requestMatchers(*OPEN_PATHS)
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, SYNC_PATH)
                    .access(requireAdapterWithComponent())
                    .requestMatchers(HttpMethod.PATCH, SYNC_PATH)
                    .access(requireAdapterWithComponent())
                    .requestMatchers(HttpMethod.DELETE, SYNC_PATH)
                    .access(requireAdapterWithComponent())
                    .anyRequest()
                    .access(requireAdapter())
            }.oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.jwtAuthenticationConverter(CorePrincipalConverter())
                }
                oauth2.authenticationEntryPoint(securityProblemDetailHandler)
                oauth2.accessDeniedHandler(securityProblemDetailHandler)
            }.exceptionHandling {
                it.authenticationEntryPoint(securityProblemDetailHandler)
                it.accessDeniedHandler(securityProblemDetailHandler)
            }.build()

    private fun requireAdapter(): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManager { authentication, context ->
            AuthorizationDecision(adapterAuthorization.isAdapter(authentication.get(), context.request))
        }

    private fun requireAdapterWithComponent(): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManager { authentication, context ->
            AuthorizationDecision(adapterAuthorization.canAccessComponent(authentication.get(), context))
        }

    private fun requireAdapterOf(orgId: String): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManager { authentication, context ->
            AuthorizationDecision(adapterAuthorization.isAdapterOf(authentication.get(), context.request, orgId))
        }

    companion object {
        private const val SYNC_PATH = "${ProviderApi.PREFIX}/{domainName}/{packageName}/{entity}"
        private const val RELATION_EDGE_REBUILD_PATH = "${AdminController.PATH}/relation-edges/rebuild"
        private const val RELATION_EDGE_DRIFT_PATH = "${AdminController.PATH}/relation-edges/drift"
        private const val RELATION_EDGE_JOB_PATH = "${AdminController.PATH}/relation-edges/jobs/{id}"
        private const val RELATION_EDGE_ADMIN_ORG_ID = "novari.no"
        private const val ADMIN_PATHS = "${AdminController.PATH}/**"
        private val OPEN_PATHS =
            arrayOf(
                "${ProviderApi.PREFIX}/swagger-ui/**",
                "${ProviderApi.PREFIX}/swagger-ui.html",
                "${ProviderApi.PREFIX}/v3/api-docs/**",
                "${ProviderApi.PREFIX}/actuator/health",
            )
    }
}
