package no.fintlabs.adapter.gateway.sync

import no.novari.core.shared.model.ResourceCoordinate
import org.springframework.data.annotation.Id
import java.time.Instant

/**
 * When a resource type last had a full sync complete. There is one document per resource type
 * per org, and the resource collection's name is the id.
 *
 * [lastCompletedAt] is empty for a resource type that has been written to but has had no full
 * sync complete since it was first seen. [createdAt] is when it was first seen. The resource TTL
 * counts from the last completed full sync, or from [createdAt] while there is none.
 */
data class FullSyncStatus(
    @Id val id: String,
    val coordinate: ResourceCoordinate,
    val lastCompletedAt: Instant? = null,
    val createdAt: Instant,
) {
    val ttlCountsFrom: Instant get() = lastCompletedAt ?: createdAt
}
