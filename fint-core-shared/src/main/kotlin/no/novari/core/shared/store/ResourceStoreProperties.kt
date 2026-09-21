package no.novari.core.shared.store

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Tunables for [ResourceStore]. [deltaHintThreshold] is the cut-over between the `last_modified`
 * and `created_at_id` indexes for a filtered read. [countCacheTtl] is how long the unfiltered
 * collection count is cached.
 */
@ConfigurationProperties(prefix = "fint.resource-store")
data class ResourceStoreProperties(
    val deltaHintThreshold: Long = 50_000,
    val countCacheTtl: Duration = Duration.ofSeconds(5),
)
