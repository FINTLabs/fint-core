package no.novari.core.shared.kafka

import no.fintlabs.adapter.models.sync.SyncType

/**
 * Structured form of the sync-related entity headers ([EntityHeaders.SYNC_TYPE],
 * [EntityHeaders.SYNC_CORRELATION_ID], [EntityHeaders.SYNC_TOTAL_SIZE]).
 *
 * Carried by every page of a single sync; absent for non-sync (event) entities.
 *
 * @property corrId unique identifier for the sync; every page in the same sync shares this value.
 * @property type the [SyncType] of the sync (e.g. full or delta).
 * @property totalSize total number of resources the sync is expected to contain.
 */
data class SyncMetadata(
    val corrId: String,
    val type: SyncType,
    val totalSize: Long,
)
