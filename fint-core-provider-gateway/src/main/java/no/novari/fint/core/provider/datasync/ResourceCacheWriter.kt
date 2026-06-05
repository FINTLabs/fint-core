package no.novari.fint.core.provider.datasync

import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.novari.fint.core.shared.autorelation.AutoRelationService
import no.novari.fint.core.shared.cache.CacheService
import no.novari.fint.core.shared.resource.ResourceConverter
import org.springframework.stereotype.Service

/**
 * Writes a synced resource straight into the shared Mongo store and keeps relations in sync, using
 * the same engine the consumer used to run off Kafka. Mirrors the consumer's EntityProcessingService:
 * a non-null resource is converted, link-mapped, upserted and its relations applied; a null resource
 * (delete) is removed and its back-links retracted.
 */
@Service
class ResourceCacheWriter(
    private val resourceConverter: ResourceConverter,
    private val cacheService: CacheService,
    private val autoRelationService: AutoRelationService,
) {
    fun write(
        resourceKey: String,
        resourceId: String,
        rawResource: Any?,
        timestamp: Long,
    ) {
        val cache = cacheService.getCache(resourceKey)
        if (rawResource == null) {
            val existing = cache.get(resourceId)
            cache.remove(resourceId, timestamp)
            if (existing != null) {
                autoRelationService.applyRemoval(resourceKey, resourceId, existing)
            }
        } else {
            val resource = resourceConverter.convertAndMapLinks(resourceKey, rawResource)
            cache.put(resourceId, resource, timestamp)
            autoRelationService.applyRelations(resourceKey, resourceId, resource)
        }
    }

    /**
     * Batch variant for a whole sync page: converts + maps links for every resource up front, then
     * applies all upserts and all deletes to the cache in one bulk round-trip each, and finally
     * reconciles relations. Avoids the per-resource round-trips of [write].
     */
    fun writeBatch(
        resourceKey: String,
        entries: List<SyncPageEntry>,
        timestamp: Long,
    ) {
        val cache = cacheService.getCache(resourceKey)
        val upserts =
            entries.mapNotNull { entry ->
                entry.resource?.let { raw -> entry.identifier to resourceConverter.convertAndMapLinks(resourceKey, raw) }
            }
        val deleteIds = entries.filter { it.resource == null }.map { it.identifier }

        cache.putAll(upserts, timestamp)
        val removed = cache.removeAll(deleteIds, timestamp)

        autoRelationService.applyRelations(resourceKey, upserts, timestamp)
        autoRelationService.applyRemoval(resourceKey, removed, timestamp)
    }
}
