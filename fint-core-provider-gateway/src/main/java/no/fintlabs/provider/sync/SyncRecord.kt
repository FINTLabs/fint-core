package no.fintlabs.provider.sync

import no.novari.core.shared.kafka.SyncMetadata
import no.novari.core.shared.model.ResourceCoordinate
import java.time.Instant

/**
 * A buffered record that belongs to a sync, in the form [SyncCompletionTracker] needs: which sync
 * it belongs to, where it sat on the buffer, and when it was written.
 *
 * [BufferReader] only builds one for a record it actually wrote to storage, so counting these is
 * the same thing as counting what reached Mongo.
 */
data class SyncRecord(
    val coordinate: ResourceCoordinate,
    val metadata: SyncMetadata,
    val partition: Int,
    val offset: Long,
    val writtenAt: Instant,
)
