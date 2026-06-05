package no.novari.fint.core.consumer.security

import no.novari.resource.server.authentication.CorePrincipal
import no.novari.resource.server.converter.CorePrincipalConverter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.RequestAuthorizationContext

@Configuration
@EnableWebSecurity
class SecurityConfiguration(
    private val securityProperties: SecurityProperties,
    private val coreAccessService: CoreAccessService,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        if (securityProperties.enabled) secured(http) else permitAll(http)

    private fun secured(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt -> jwt.jwtAuthenticationConverter(CorePrincipalConverter()) }
            }.authorizeHttpRequests { requests ->
                securityProperties.exposedEndpoints
                    ?.toTypedArray()
                    ?.let { requests.requestMatchers(*it).permitAll() }
                requests.anyRequest().access(coreAccess())
            }.build()

    private fun coreAccess(): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManager { authentication, context ->
            val principal = authentication.get()
            AuthorizationDecision(principal is CorePrincipal && coreAccessService.authorize(principal, context.request))
        }

    private fun permitAll(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .build()
}
