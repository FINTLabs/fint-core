package no.fintlabs.autorelation

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import no.fintlabs.autorelation.model.MetricReason
import no.fintlabs.autorelation.model.RelationOperation
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class MetricService(
    private val meterRegistry: MeterRegistry,
) {
    private val counters = ConcurrentHashMap<String, Counter>()

    fun incrementRuleSkipped(
        resourceName: String,
        reason: MetricReason,
    ) = counter(
        "fint.autorelation.rule.skipped",
        listOf(
            Tag.of("resource", resourceName),
            Tag.of("reason", reason.tagValue),
        ),
    ).increment()

    fun incrementUpdateApplied(
        resourceName: String,
        operation: RelationOperation,
    ) = counter(
        "fint.autorelation.update.applied",
        listOf(
            Tag.of("resource", resourceName),
            Tag.of("operation", operation.tagValue),
        ),
    ).increment()

    fun incrementUpdateBuffered(
        resourceName: String,
        operation: RelationOperation,
    ) = counter(
        "fint.autorelation.update.buffered",
        listOf(
            Tag.of("resource", resourceName),
            Tag.of("operation", operation.tagValue),
        ),
    ).increment()

    fun incrementUpdateFailed(
        resourceName: String,
        operation: RelationOperation,
        reason: MetricReason,
    ) = counter(
        "fint.autorelation.update.failed",
        listOf(
            Tag.of("resource", resourceName),
            Tag.of("operation", operation.tagValue),
            Tag.of("reason", reason.tagValue),
        ),
    ).increment()

    fun incrementBufferExpired(
        resourceName: String,
        relationName: String,
        linkCount: Int,
    ) = counter(
        "fint.autorelation.buffer.expired",
        listOf(
            Tag.of("resource", resourceName),
            Tag.of("relation", relationName),
        ),
    ).increment(linkCount.toDouble())

    fun incrementHydratedLinks(
        resourceName: String,
        relationName: String,
        linkCount: Int,
    ) = counter(
        "fint.autorelation.reconcile.hydrated_links",
        listOf(
            Tag.of("resource", resourceName),
            Tag.of("relation", relationName),
        ),
    ).increment(linkCount.toDouble())

    fun incrementPreservedLinks(
        resourceName: String,
        relationName: String,
        linkCount: Int,
    ) = counter(
        "fint.autorelation.reconcile.preserved_links",
        listOf(
            Tag.of("resource", resourceName),
            Tag.of("relation", relationName),
        ),
    ).increment(linkCount.toDouble())

    fun registerBufferSizeGauge(supplier: () -> Number): Gauge =
        Gauge
            .builder("fint.autorelation.buffer.size", supplier)
            .description("Current number of buffered relation keys awaiting target arrival")
            .register(meterRegistry)

    fun incrementCachePutRejectedOlderTimestamp(resourceName: String) =
        counter(
            "fint.consumer.cache.put_rejected_older_timestamp",
            listOf(Tag.of("resource", resourceName)),
        ).increment()

    private fun counter(
        name: String,
        tags: List<Tag>,
    ): Counter = counters.computeIfAbsent(meterKey(name, tags)) { meterRegistry.counter(name, tags) }

    private fun meterKey(
        name: String,
        tags: List<Tag>,
    ): String = "$name|${tags.joinToString("|") { "${it.key}=${it.value}" }}"
}
