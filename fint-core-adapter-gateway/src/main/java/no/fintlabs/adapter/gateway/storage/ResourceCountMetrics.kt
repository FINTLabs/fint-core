package no.fintlabs.adapter.gateway.storage

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.org.OrgStore
import no.novari.core.shared.store.ResourceStore
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes the count for each resource as a Prometheus gauge.
 * The metric can be found in prometheus as `core_resource_count`
 *
 * Counts are read at startup and refreshed periodically so they recover after a restart and reflect
 * removals through deletes, eviction, and collection wipes.
 *
 * The refresh interval defaults to five minutes and can be changed with `fint.provider.resource-count-metrics.interval`.
 */
@Component
class ResourceCountMetrics(
    private val resourceStore: ResourceStore,
    private val orgStore: OrgStore,
    private val meterRegistry: MeterRegistry,
) {
    private data class Key(
        val orgId: String,
        val resource: String,
    )

    private val publishedCounts = ConcurrentHashMap<Key, AtomicLong>()

    /**
     * Refreshes every resource count for all orgs recorded in this gateway's store.
     * Initial value on startup, followed by updates at the configured interval.
     */
    @EventListener(ApplicationReadyEvent::class)
    @Scheduled(
        fixedDelayString = $$"${fint.provider.resource-count-metrics.interval:PT30S}",
        initialDelayString = $$"${fint.provider.resource-count-metrics.interval:PT30S}",
    )
    @Synchronized
    fun refresh() {
        val seen = mutableSetOf<Key>()

        orgStore.findAll().forEach { org ->
            val orgId = OrgId.from(org.id)
            resourceStore.storedCoordinates(orgId).forEach { coordinate ->
                val key = Key(orgId.value, coordinate.toResourceUri())
                seen += key

                val currentCount = resourceStore.getCacheSize(coordinate)

                // If the gauge doesn't exist, register it.
                val publishedCount =
                    publishedCounts.computeIfAbsent(key) {
                        registerGauge(key, coordinate, currentCount)
                    }

                // Update the gauge value.
                publishedCount.set(currentCount)
            }
        }

        // A resource wipe drops its collection, so it no longer appears in storedCoordinates.
        // Keep its already-registered time series and publish zero instead of leaving a stale count.
        publishedCounts.forEach { (key, publishedCount) ->
            if (key !in seen) publishedCount.set(0)
        }
    }

    /** Registers a gauge initialized with its first database count and returns the `AtomicLong`. */
    private fun registerGauge(
        key: Key,
        coordinate: ResourceCoordinate,
        initialCount: Long,
    ): AtomicLong {
        val publishedCount = AtomicLong(initialCount)
        Gauge
            .builder("core.resource.count", publishedCount) {
                it.get().toDouble()
            }.description("Number of resources stored for this FINT resource type")
            .tags(
                "org",
                key.orgId,
                "domain",
                coordinate.domainName,
                "package",
                coordinate.packageName,
                "resource",
                coordinate.resourceName,
            ).register(meterRegistry)

        return publishedCount
    }
}
