package no.fintlabs.consumer.security

import no.novari.resource.server.enums.FintScope
import no.novari.resource.server.enums.FintType
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("fint.security")
class SecurityProperties {
    var enabled: Boolean = true
    var fintType: FintType? = null
    var requiredScopes: List<FintScope>? = null
    var exposedEndpoints: List<String>? = null
}
