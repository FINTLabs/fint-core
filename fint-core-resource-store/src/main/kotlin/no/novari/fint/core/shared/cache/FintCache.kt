package no.novari.fint.core.shared.cache

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

    /**
     * Bulk variant of [put]: conditionally upserts every entry (sharing [timestamp]) in one
     * round-trip. Entries with a newer stored timestamp are left unchanged.
     */
    fun putAll(
        items: List<Pair<String, FintResource>>,
        timestamp: Long,
    )

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
     * Batched [findIdsByBackLink]: resolves the back-link holders for every ref in [refs] under
     * [relation] in a single query, returning a map from ref to the ids pointing back to it. Refs
     * with no holder are absent from the map. Used to collapse a sync page's per-source lookups.
     */
    fun findIdsByBackLinks(
        relation: String,
        refs: Set<String>,
    ): Map<String, Set<String>>

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

    /**
     * Bulk back-link reconciliation: applies all [ops] (adds/removes) to this collection in a single
     * round-trip. Adds upsert a stub for an absent target; removes never create one.
     */
    fun applyBackLinkOps(
        ops: List<BackLinkOp>,
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

    /**
     * Bulk variant of [remove]: deletes every id (timestamp guard) in one round-trip and returns the
     * entries that existed beforehand, so callers can retract their back-links.
     */
    fun removeAll(
        resourceIds: List<String>,
        timestamp: Long,
    ): List<Pair<String, FintResource>>

    fun evictExpired(timestamp: Long): Set<Pair<String, FintResource>>
}
