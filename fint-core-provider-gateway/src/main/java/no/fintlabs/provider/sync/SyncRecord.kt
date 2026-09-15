package no.fintlabs.provider.sync

import no.novari.core.shared.kafka.SyncMetadata
import no.novari.core.shared.model.ResourceCoordinate
import java.time.Instant

data class SyncRecord(
    val coordinate: ResourceCoordinate,
    val metadata: SyncMetadata,
    val partition: Int,
    val offset: Long,
    val writtenAt: Instant,
)
