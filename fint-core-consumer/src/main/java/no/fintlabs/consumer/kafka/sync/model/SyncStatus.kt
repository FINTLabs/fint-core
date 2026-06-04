package no.fintlabs.consumer.kafka.sync.model

import no.fintlabs.adapter.models.sync.SyncType

/**
 * Represents the status of a synchronization process.
 *
 * <p>This class is used to send messages about successful or failed sync operations
 * based on their completion status. Each {@code SyncStatus} instance includes a
 * correlation ID, a synchronization type, and a resulting status.</p>
 *
 * @property corrId A unique identifier for correlating sync messages.
 * @property type The type of synchronization (defined by {@link SyncType}).
 * @property status The outcome status of the sync process.
 */
data class SyncStatus(
    val corrId: String,
    val type: SyncType,
    val status: String,
)
