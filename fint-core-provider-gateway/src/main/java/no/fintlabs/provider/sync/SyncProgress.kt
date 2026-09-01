package no.fintlabs.provider.sync

import no.novari.core.shared.model.ResourceCoordinate
import org.springframework.data.annotation.Id
import java.time.Instant

/**
 * How far one full sync has got, shared by every replica working on it.
 *
 * A sync is only finished when every resource it announced is in storage, and its pages reach
 * storage through the buffer topic, so no single replica can tell on its own. This document is
 * where they agree: each replica folds the records it wrote into [processed], and whoever pushes
 * it to [totalSize] evicts.
 *
 * @property offsets the highest buffer offset folded in, per partition. Kafka redelivers records
 *  that were written but whose offset was not committed, and folding one twice would push
 *  [processed] past [totalSize] before the sync was really done. Keyed by partition because
 *  offsets only ever mean anything within one.
 * @property startedAt the earliest moment any of this sync's records was written. Everything the
 *  sync carried is at least this new, so anything older is what eviction takes out.
 * @property evictedAt when a replica claimed the eviction, absent while unclaimed. Claiming it is
 *  what makes the sweep run once no matter how many replicas notice the sync finished.
 */
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
