package no.fintlabs.provider.datasync

import no.fintlabs.autorelation.AutoRelationService
import no.fintlabs.cache.CacheService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

/**
 * Removes cache entries left stale by a completed full sync and retracts their back-links, mirroring
 * the consumer's CacheEvictionService. `evictExpired` deletes everything with `timestamp < threshold`
 * (the sync's earliest write time), so entries (re)written during the sync survive. Runs async so the
 * adapter's sync request does not wait on the sweep.
 */
@Service
class ProviderEvictionService(
    private val cacheService: CacheService,
    private val autoRelationService: AutoRelationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async("evictionTaskExecutor")
    fun evictExpired(
        resourceKey: String,
        startTimestamp: Long,
    ) {
        try {
            cacheService
                .getCache(resourceKey)
                .evictExpired(startTimestamp)
                .forEach { (resourceId, resource) ->
                    autoRelationService.applyRemoval(resourceKey, resourceId, resource)
                }
        } catch (e: RuntimeException) {
            log.error("Cache eviction failed: resource={}, startTimestamp={}", resourceKey, startTimestamp, e)
        }
    }
}
