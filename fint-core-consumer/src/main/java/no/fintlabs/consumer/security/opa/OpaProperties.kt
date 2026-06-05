package no.fintlabs.consumer.security.opa

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("fint.security.opa")
class OpaProperties {
    var enabled: Boolean = false
    var filter: Boolean = true
    var url: String = "http://fint-core-opa.fint-core.svc.cluster.local:8181"
    var envHeader: Boolean = false
}
