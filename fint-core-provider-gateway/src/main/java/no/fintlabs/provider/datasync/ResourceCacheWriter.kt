package no.fintlabs.provider.datasync

import no.fintlabs.autorelation.AutoRelationService
import no.fintlabs.cache.CacheService
import no.fintlabs.consumer.resource.ResourceConverter
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
}
