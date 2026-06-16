package no.fintlabs.consumer.resource

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import no.fintlabs.cache.CacheService
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.kafka.sync.LastCompletedFullSyncCache
import no.fintlabs.consumer.resource.context.ResourceContext
import org.springframework.stereotype.Component

@Component
class ResourceMetrics(
    private val meterRegistry: MeterRegistry,
    private val cacheService: CacheService,
    private val resourceContext: ResourceContext,
    private val configuration: ConsumerConfiguration,
    private val lastFullSyncCache: LastCompletedFullSyncCache,
) {
    @PostConstruct
    private fun init() {
        resourceContext.resources.forEach { resource ->
            val name = resource.name()
            registerCacheSize(name)
            registerLatestFullSync(name)
        }
    }

    private fun registerCacheSize(resourceName: String) =
        registerGauge(
            name = "core.cache.size",
            resourceName = resourceName,
            description = "Number of entries in the cache for a given resource",
        ) { cacheService.getCache(resourceName).size }

    private fun registerLatestFullSync(resourceName: String) =
        registerGauge(
            name = "core.consumer.latestCompletedFullSync",
            resourceName = resourceName,
            description = "Timestamp of latest completed FullSync for $resourceName",
        ) { lastFullSyncCache.getLatestFromResource(resourceName) }

    private fun registerGauge(
        name: String,
        resourceName: String,
        description: String,
        supplier: () -> Number,
    ) = Gauge
        .builder(name, supplier)
        .tag("resource", resourceName)
        .tag("org", configuration.orgId.value)
        .description(description)
        .register(meterRegistry)
}
