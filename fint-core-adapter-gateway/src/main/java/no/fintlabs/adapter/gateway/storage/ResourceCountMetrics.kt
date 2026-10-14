package no.fintlabs.adapter.gateway.storage

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.org.OrgStore
import no.novari.core.shared.store.ResourceStore
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes the number of stored resources for each resource collection as a Prometheus gauge.
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

    private val values = ConcurrentHashMap<Key, AtomicLong>()

    /**
     * Refreshes every existing resource collection for the orgs recorded in this gateway's store.
     * The startup event provides an initial value; the scheduled invocation reconciles the gauges
     * with MongoDB after subsequent resource changes.
     */
    @EventListener(ApplicationReadyEvent::class)
    @Scheduled(
        fixedDelayString = $$"${fint.provider.resource-count-metrics.interval:PT5M}",
    )
    @Synchronized
    fun refresh() {
        val seen = mutableSetOf<Key>()

        orgStore.findAll().forEach { org ->
            val orgId = OrgId.from(org.id)
            resourceStore.storedCoordinates(orgId).forEach { coordinate ->
                val key = Key(orgId.value, coordinate.toResourceUri())
                seen += key

                val value =
                    values.computeIfAbsent(key) {
                        AtomicLong().also { backing ->
                            Gauge
                                .builder("core.resource.count", backing) {
                                    it.get().toDouble()
                                }.description("Number of resources stored for this FINT resource type")
                                .tags(
                                    "org",
                                    orgId.value,
                                    "domain",
                                    coordinate.domainName,
                                    "package",
                                    coordinate.packageName,
                                    "resource",
                                    coordinate.resourceName,
                                ).register(meterRegistry)
                        }
                    }

                value.set(resourceStore.getCacheSize(coordinate))
            }
        }

        // A resource wipe drops its collection, so it no longer appears in storedCoordinates.
        // Keep its already-registered time series and publish zero instead of leaving a stale count.
        values.forEach { (key, value) ->
            if (key !in seen) value.set(0)
        }
    }
}
