package no.fintlabs.adapter.gateway.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Tunables for eviction. [batchSize] is how many resources one eviction transaction removes,
 * together with the relation edges they own. Every edge counts as a modified document too, so a
 * resource type with many relations may want a smaller batch.
 */
@ConfigurationProperties(prefix = "fint.provider.eviction")
data class EvictionProperties(
    val batchSize: Int = 1000,
)
