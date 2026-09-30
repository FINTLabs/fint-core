package no.fintlabs.adapter.gateway.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Settings for removing resources that no adapter has delivered for a long time. A resource that
 * was last delivered more than [maxAge] ago is removed, together with the relation edges it owns.
 *
 * [enabled] turns the sweep off completely. How often the sweep runs is set with
 * `fint.provider.resource-ttl.sweep-interval`, one hour by default.
 */
@ConfigurationProperties(prefix = "fint.provider.resource-ttl")
data class ResourceTtlProperties(
    val enabled: Boolean = true,
    val maxAge: Duration = Duration.ofDays(21),
)
