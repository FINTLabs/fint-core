package no.novari.core.shared.store

import no.novari.fint.core.model.FintResource
import java.time.Instant

/**
 * Represents a single write operation intended for storing a resource in a specified collection.
 *
 * This data class encapsulates metadata and information about a resource write operation,
 * including the unique identifier of the resource, the name of the target collection in the database,
 * the resource payload itself, and the timestamp of when the write is performed.
 *
 * @property resourceId The unique identifier of the resource.
 * @property collectionName The name of the collection where the resource is to be stored.
 * @property resource The resource data to be written, represented as a `FintResource`.
 * @property timestamp The timestamp indicating when this write operation was executed.
 */
data class ResourceWrite(
    val resourceId: String,
    val collectionName: String,
    val resource: FintResource,
    val timestamp: Instant = Instant.now(),
)
