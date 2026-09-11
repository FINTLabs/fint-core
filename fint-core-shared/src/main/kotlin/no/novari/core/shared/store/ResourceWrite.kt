package no.novari.core.shared.store

import no.novari.fint.core.model.FintResource
import java.time.Instant

sealed interface ResourceWrite {
    val resourceId: String
    val collectionName: String
    val timestamp: Instant
}

data class Save(
    override val resourceId: String,
    override val collectionName: String,
    val resource: FintResource,
    override val timestamp: Instant = Instant.now(),
) : ResourceWrite

data class Delete(
    override val resourceId: String,
    override val collectionName: String,
    override val timestamp: Instant = Instant.now(),
) : ResourceWrite
