package no.fintlabs.provider.sync

import no.novari.core.shared.model.ResourceCoordinate
import org.springframework.data.annotation.Id
import java.time.Instant

data class SyncProgress(
    @Id val corrId: String,
    val coordinate: ResourceCoordinate,
    val totalSize: Long,
    val processed: Long = 0,
    val offsets: Map<String, Long> = emptyMap(),
    val startedAt: Instant,
    val updatedAt: Instant,
    val evictedAt: Instant? = null,
) {
    val complete: Boolean get() = processed >= totalSize
}
