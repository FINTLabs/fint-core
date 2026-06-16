package no.fintlabs.consumer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "fint.consumer.event")
data class EventCacheProperties(
    /** Fallback settings used when no resource-specific config is present. */
    val defaults: LifeCycle = LifeCycle(),
    /** Specific settings per resource (key: resource name). */
    val resources: Map<String, LifeCycle> = emptyMap(),
) {
    fun getLifeCycleConfig(resourceName: String): LifeCycle = resources[resourceName] ?: defaults

    data class LifeCycle(
        /** * Duration to retain the event in memory for status observability.
         * Default: 30 minutes.
         */
        var eviction: Duration = Duration.ofMinutes(30),
        /** * Max duration the event is valid for downstream processing.
         * Default: 2 minutes.
         */
        var ttl: Duration = Duration.ofMinutes(2),
    )
}
