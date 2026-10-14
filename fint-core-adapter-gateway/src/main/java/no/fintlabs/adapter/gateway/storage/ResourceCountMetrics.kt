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

    @EventListener(ApplicationReadyEvent::class)
    @Scheduled(
        fixedDelayString = $$"${fint.provider.resource-count-metrics.interval:PT5M}",
    )
    fun refresh() {
        val seen = mutableSetOf<Key>()

        orgStore.findAll().forEach { org ->
            resourceStore.storedCoordinates(OrgId.from(org.id)).forEach { coordinate ->
                val key = Key(org.id, coordinate.toResourceUri())
                seen += key

                val value =
                    values.computeIfAbsent(key) {
                        AtomicLong().also { backing ->
                            Gauge
                                .builder("core.resource.count", backing) {
                                    it.get().toDouble()
                                }.tags(
                                    "org",
                                    org.id,
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

        // A wipe can drop a collection. Keep its previously registered gauge at zero.
        values.forEach { (key, value) ->
            if (key !in seen) value.set(0)
        }
    }
}
