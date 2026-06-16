package no.fintlabs.cache

import no.fint.antlr.odata.ODataFilterService
import no.novari.fint.model.resource.FintResource
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.stream.Stream
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.max

/**
 * Thread-safe in-memory cache for [FintResource] instances.
 *
 * Entries are iterated in ascending order of `(timestamp, resourceId)`. When multiple
 * partitions produce records concurrently the insertion order is no longer meaningful, so
 * the cache uses a [TreeMap] keyed by [SortKey] for sorted iteration and a [HashMap] for
 * O(1) lookup by resource ID. Using `resourceId` as the tiebreaker gives a stable, unique
 * ordering even when two records share the same timestamp, without requiring access to the
 * concrete resource type.
 *
 * A secondary index by identifier key/value supports fast [getByIdField] lookups.
 * Each entry carries the Kafka record timestamp used for incremental reads
 * ([sinceTimestamp]), expiration ([evictExpired]), and last-update tracking.
 */
class FintCache<T : FintResource> {
    private val index: MutableMap<IndexKey, CacheEntry> = mutableMapOf()
    private val entryStore: HashMap<String, CacheEntry> = HashMap()
    private val sortedEntries: TreeMap<SortKey, CacheEntry> = TreeMap()
    private val lastUpdatedTimestamp = AtomicLong(0L)
    private val lock = ReentrantReadWriteLock()
    private val oDataFilterService = ODataFilterService()

    /**
     * Composite sort key for [sortedEntries].
     *
     * Primary sort is by [timestamp] ascending. [resourceId] is the tiebreaker so that
     * two entries with the same timestamp always have a distinct, stable position.
     */
    private data class SortKey(
        val timestamp: Long,
        val resourceId: String,
    ) : Comparable<SortKey> {
        override fun compareTo(other: SortKey): Int {
            val cmp = timestamp.compareTo(other.timestamp)
            return if (cmp != 0) cmp else resourceId.compareTo(other.resourceId)
        }
    }

    /**
     * Internal cache value containing the resource and its write timestamp.
     */
    inner class CacheEntry(
        /** Cached resource instance. */
        val resource: T,
        /** Timestamp used for change tracking, filtering, and eviction. */
        val timestamp: Long,
    )

    /**
     * Composite key for [index], based on identifier key and identifier value.
     *
     * The identifier key is normalized to lowercase to make lookups case-insensitive.
     */
    private class IndexKey(
        idKey: String,
        val idValue: Any,
    ) {
        val idKey: String = idKey.lowercase()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IndexKey) return false
            return idKey == other.idKey && idValue == other.idValue
        }

        override fun hashCode(): Int = 31 * idKey.hashCode() + idValue.hashCode()
    }

    /**
     * Insert or replace a resource in the cache.
     *
     * When replacing an existing entry the old [SortKey] is removed from [sortedEntries]
     * before inserting the new one, so the sorted view always reflects the current timestamp.
     * Updates the identifier index and advances [lastUpdated] with the provided timestamp.
     *
     * @return `true` if the write was accepted, `false` if it was rejected because an existing
     *   entry has a newer timestamp. A `false` return is a silent-loss signal callers may surface
     *   via a metric; callers that don't care can ignore it.
     */
    fun put(
        resourceId: String,
        resource: T,
        timestamp: Long,
    ): Boolean {
        val entry = CacheEntry(resource, timestamp)

        return lock.write {
            val existing = entryStore[resourceId]
            if (existing != null) {
                if (timestamp < existing.timestamp) return@write false
                sortedEntries.remove(SortKey(existing.timestamp, resourceId))
                removeFromIndexes(existing.resource)
            }
            entryStore[resourceId] = entry
            sortedEntries[SortKey(timestamp, resourceId)] = entry
            updateIndexes(entry)
            lastUpdated = timestamp
            true
        }
    }

    /**
     * Get a cached resource by resource ID.
     *
     * @return the cached resource, or `null` if not present.
     */
    fun get(resourceId: String): T? =
        lock.read {
            entryStore[resourceId]?.resource
        }

    /**
     * Get the write timestamp for a cached resource.
     *
     * @return timestamp of the cached resource, or `null` if not present.
     */
    fun lastUpdatedByResourceId(resourceId: String): Long? =
        lock.read {
            entryStore[resourceId]?.timestamp
        }

    /**
     * Get a cached resource by identifier field and value.
     *
     * Identifier field matching is case-insensitive.
     */
    fun getByIdField(
        field: String,
        value: Any,
    ): T? =
        lock.read {
            index[IndexKey(field, value)]?.resource
        }

    /**
     * Get a paged, timestamp-sorted list of cached resources, optionally filtered by
     * timestamp and OData filter.
     *
     * Results are always returned in ascending `(timestamp, resourceId)` order. When
     * [sinceTimestamp] is greater than `0`, a [TreeMap.tailMap] is used to efficiently
     * skip entries older than that timestamp. When [size] is greater than `0`, pagination
     * is applied using [offset] and [size].
     */
    fun getList(
        size: Long,
        offset: Long,
        sinceTimestamp: Long,
        filter: String?,
    ): List<T> =
        lock.read {
            val entriesView: Collection<CacheEntry> =
                if (sinceTimestamp > 0L) {
                    // tailMap includes all keys >= SortKey(sinceTimestamp, "").
                    // Since "" precedes every real resource ID, all entries whose timestamp
                    // equals sinceTimestamp are included.
                    sortedEntries.tailMap(SortKey(sinceTimestamp, "")).values
                } else {
                    sortedEntries.values
                }

            var resources: Stream<T> = entriesView.stream().map { it.resource }
            if (filter != null && !filter.isBlank()) {
                // Only include entries matching OData $filter
                resources = applyODataFilter(resources, filter)
            }

            if (size > 0) {
                // Only include entries for requested page
                if (offset > 0) {
                    resources = resources.skip(offset)
                }
                resources = resources.limit(size)
            }

            resources.toList()
        }

    private fun applyODataFilter(
        resources: Stream<T>,
        filter: String,
    ): Stream<T> {
        if (!oDataFilterService.validate(filter)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid OData filter")
        }

        return oDataFilterService.from(resources, filter)
    }

    /**
     * Highest timestamp seen by the cache from write/remove operations.
     */
    var lastUpdated: Long
        get() =
            lock.read {
                return lastUpdatedTimestamp.get()
            }
        private set(value) =
            lock.write {
                lastUpdatedTimestamp.accumulateAndGet(value) { existing, new -> max(existing, new) }
            }

    /**
     * Current number of cached resources.
     */
    val size: Int
        get() = lock.read { entryStore.size }

    /**
     * Remove a resource by ID.
     *
     * If the resource exists, its [SortKey] is removed from [sortedEntries], its index
     * entries are removed, and [lastUpdated] is advanced with the provided timestamp.
     */
    fun remove(
        resourceId: String,
        timestamp: Long,
    ) = lock.write {
        val entry = entryStore[resourceId]
        if (entry != null && timestamp > entry.timestamp) {
            entryStore.remove(resourceId)
            sortedEntries.remove(SortKey(entry.timestamp, resourceId))
            removeFromIndexes(entry.resource)
            lastUpdated = timestamp
        }
    }

    /**
     * Evict expired cache entries. A cached entry is considered expired if it has a timestamp
     * older than the earliest timestamp of a full-sync.
     *
     * Uses [TreeMap.headMap] to efficiently find all entries with
     * `timestamp < evictionTimestamp` without scanning the entire cache.
     *
     * @param timestamp earliest timestamp of a full-sync.
     * @return evicted resources
     */
    fun evictExpired(timestamp: Long): Set<Pair<String, T>> =
        lock.write {
            // headMap is exclusive of the toKey. SortKey(timestamp, "") is less than any
            // real entry at that timestamp (since "" < any non-empty resourceId), so this
            // gives exactly the entries where entry.timestamp < timestamp.
            val expired = sortedEntries.headMap(SortKey(timestamp, "")).entries.toList()
            val removedResources = mutableSetOf<Pair<String, T>>()

            for ((sortKey, entry) in expired) {
                val resourceId = sortKey.resourceId
                removedResources.add(Pair(resourceId, entry.resource))
                entryStore.remove(resourceId)
                sortedEntries.remove(sortKey)
                removeFromIndexes(entry.resource)
            }

            removedResources
        }

    private fun updateIndexes(entry: CacheEntry) {
        entry.resource.identifikators
            .filter { entry -> entry.value?.identifikatorverdi != null }
            .forEach { (key, value) ->
                index[IndexKey(key, value.identifikatorverdi)] = entry
            }
    }

    private fun removeFromIndexes(resource: T) {
        resource.identifikators
            .filter { entry -> entry.value?.identifikatorverdi != null }
            .forEach { (key, value) ->
                index.remove(IndexKey(key, value.identifikatorverdi))
            }
    }
}
