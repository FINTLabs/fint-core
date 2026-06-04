package no.fintlabs.autorelation

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import no.fintlabs.autorelation.model.MetricReason
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
        action: String,
    ) = counter(
        "fint.autorelation.update.applied",
        listOf(
            Tag.of("resource", resourceName),
            Tag.of("action", action),
        ),
    ).increment()

    fun incrementUpdateFailed(
        resourceName: String,
        reason: MetricReason,
    ) = counter(
        "fint.autorelation.update.failed",
        listOf(
            Tag.of("resource", resourceName),
            Tag.of("reason", reason.tagValue),
        ),
    ).increment()

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
