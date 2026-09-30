package no.fintlabs.adapter.gateway.security

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

    companion object {
        private const val SYNC_PATH = "/{domainName}/{packageName}/{entity}"
        private val OPEN_PATHS =
            arrayOf(
                "/swagger-ui/**",
                "/swagger-ui.html",
                "/v3/api-docs/**",
                "/actuator/health",
            )
    }
}
