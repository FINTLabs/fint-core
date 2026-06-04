package no.fintlabs.cache

import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link

interface FintCache {
    val lastUpdated: Long
    val size: Int

    fun put(
        resourceId: String,
        resource: FintResource,
        timestamp: Long,
    ): Boolean

    fun get(resourceId: String): FintResource?

    fun lastUpdatedByResourceId(resourceId: String): Long?

    fun getByIdField(
        field: String,
        value: Any,
    ): FintResource?

    /**
     * Ids of cached resources currently holding a back-link under [relation] pointing to [ref].
     * Used by the auto-relation system to diff the desired back-link set against what is stored.
     */
    fun findIdsByBackLink(
        relation: String,
        ref: String,
    ): Set<String>

    /**
     * Atomically add (or replace) a back-link under [relation] on resource [resourceId], upserting a
     * data-less stub if the target has not yet been cached. [timestamp] is the relation-event time,
     * applied to the target's timestamp only when it already holds data so incremental readers see
     * the change. Idempotent: an existing back-link with the same target is replaced, not duplicated.
     */
    fun addBackLink(
        resourceId: String,
        relation: String,
        link: Link,
        timestamp: Long,
    )

    /**
     * Atomically remove the back-link under [relation] pointing to [ref] from resource [resourceId].
     * No-op if the resource or back-link is absent. Never creates a stub.
     */
    fun removeBackLink(
        resourceId: String,
        relation: String,
        ref: String,
        timestamp: Long,
    )

    fun getList(
        size: Long,
        offset: Long,
        sinceTimestamp: Long,
        filter: String?,
    ): List<FintResource>

    fun remove(
        resourceId: String,
        timestamp: Long,
    )

    fun evictExpired(timestamp: Long): Set<Pair<String, FintResource>>
}
