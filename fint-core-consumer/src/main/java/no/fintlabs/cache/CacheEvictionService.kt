package no.fintlabs.cache

import no.fintlabs.autorelation.AutoRelationService
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.novari.fint.model.resource.FintResource
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Service
class CacheEvictionService(
    private val cacheService: CacheService,
    private val autoRelationService: AutoRelationService,
    private val consumerConfiguration: ConsumerConfiguration,
) {
    private val evictionStates = ConcurrentHashMap<String, EvictionState>()

    @Async("evictionTaskExecutor")
    fun evictExpired(
        resourceName: String,
        startTimestamp: Long,
    ) {
        val state = evictionStates.computeIfAbsent(resourceName) { EvictionState() }
        state.latestStartTimestamp.accumulateAndGet(startTimestamp) { existing, incoming -> maxOf(existing, incoming) }

        state.pending.set(true)
        if (!state.running.compareAndSet(false, true)) {
            logger.info(
                "Cache eviction already running, queued a new run: org={}, resource={}",
                consumerConfiguration.orgId,
                safeResourceName(resourceName),
            )
            return
        }

        do {
            state.pending.set(false)
            val runStartTimestamp = state.latestStartTimestamp.get()
            try {
                evictExpiredInternal(resourceName, runStartTimestamp)
            } catch (runtimeException: RuntimeException) {
                logger.error(
                    "Async cache eviction failed: org={}, resource={}, startTimestamp={}",
                    consumerConfiguration.orgId,
                    safeResourceName(resourceName),
                    runStartTimestamp,
                    runtimeException,
                )
            }
            state.running.set(false)
        } while (state.pending.get() && state.running.compareAndSet(false, true))
    }

    private fun evictExpiredInternal(
        resourceName: String,
        startTimestamp: Long,
    ) {
        val cache = cacheService.getCache(resourceName)

        cache
            .evictExpired(startTimestamp)
            .forEach {
                if (consumerConfiguration.autorelation.enabled) {
                    publishRelationDeleteRequest(resourceName, it.first, it.second)
                }
            }
    }

    private fun publishRelationDeleteRequest(
        resourceName: String,
        resourceId: String,
        resource: FintResource,
    ) = autoRelationService.applyRemoval(resourceName, resourceId, resource)

    private fun safeResourceName(resourceName: String?): String = resourceName?.takeIf { it.isNotBlank() } ?: "unknown"

    companion object {
        private val logger = LoggerFactory.getLogger(CacheEvictionService::class.java)
    }

    private class EvictionState(
        val running: AtomicBoolean = AtomicBoolean(false),
        val pending: AtomicBoolean = AtomicBoolean(false),
        val latestStartTimestamp: AtomicLong = AtomicLong(Long.MIN_VALUE),
    )
}
