package no.novari.fint.core.provider.datasync.ingest

import no.fintlabs.adapter.models.sync.SyncType

data class SyncIngestRecord(
    val resourceKey: String,
    val identifier: String,
    val orgId: String?,
    val corrId: String?,
    val syncType: SyncType,
    val totalSize: Long,
    val time: Long,
    val resource: Any?,
)
