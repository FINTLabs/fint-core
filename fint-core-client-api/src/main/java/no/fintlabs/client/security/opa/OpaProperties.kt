package no.fintlabs.client.security.opa

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** When [enabled] is false, [OpaService] allows everything, so only the checks already in `SecurityConfiguration` apply. */
@ConfigurationProperties("fint.security.opa")
class OpaProperties {
    var enabled: Boolean = false
    var url: String = "http://fint-core-opa.fint-core.svc.cluster.local:8181"
    var timeout: Duration = Duration.ofSeconds(2)
}
