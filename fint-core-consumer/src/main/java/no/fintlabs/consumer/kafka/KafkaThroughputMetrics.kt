package no.fintlabs.consumer.kafka

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Transport-level Kafka throughput metrics for the relation-update flow.
 *
 * These metrics measure what happens at the Kafka boundary: how many records the producer
 * sent, how many the consumer pulled in, and how long each took to process. Per-target
 * business outcomes (applied / buffered / failed-with-reason) live in
 * [no.fintlabs.autorelation.MetricService] instead, since one consumed record fans out to
 * N targets and each target has its own outcome.
 *
 * The intended debugging shape is to join across the two: comparing
 * `relation_update.produced{outcome="published"}` against the sum of `update.applied`,
 * `update.buffered`, and `update.failed` per resource surfaces "where did relation
 * updates disappear" between the producer and the per-target verdict.
 */
@Service
class KafkaThroughputMetrics(
    private val meterRegistry: MeterRegistry,
) {
    private val counters = ConcurrentHashMap<String, Counter>()
    private val timers = ConcurrentHashMap<String, Timer>()

    /**
     * Records a consumed relation-update record: increments the records counter and
     * records the processing duration.
     *
     * Tagged by [targetResource] only — outcome is intentionally omitted because the
     * consumer call swallows per-target failures internally (see
     * [no.fintlabs.autorelation.AutoRelationService]), so a single record-level
     * success/failed tag would be misleading. Per-target outcomes live in
     * [no.fintlabs.autorelation.MetricService].
     */
    fun recordRelationUpdateConsumer(
        targetResource: String?,
        durationNs: Long,
    ) {
        val tags = listOf(Tag.of("resource", normalizeResource(targetResource)))
        counter("fint.consumer.kafka.relation_update.records", tags).increment()
        timer("fint.consumer.kafka.relation_update.processing.duration", tags).record(durationNs, TimeUnit.NANOSECONDS)
    }

    /**
     * Records a produced relation-update from the producer side, tagged by
     * [targetResource], [operation] (`add` / `delete`), and [outcome]
     * (`published` / `failed`).
     *
     * Pairs with [recordRelationUpdateConsumer] for the produced-vs-consumed diff that
     * shows whether records reach the consumer at all.
     */
    fun recordRelationUpdateProduced(
        targetResource: String?,
        operation: String,
        outcome: String,
    ) {
        val tags =
            listOf(
                Tag.of("resource", normalizeResource(targetResource)),
                Tag.of("operation", operation.lowercase()),
                Tag.of("outcome", outcome),
            )
        counter("fint.consumer.kafka.relation_update.produced", tags).increment()
    }

    private fun counter(
        name: String,
        tags: List<Tag>,
    ): Counter = counters.computeIfAbsent(meterKey(name, tags)) { meterRegistry.counter(name, tags) }

    private fun timer(
        name: String,
        tags: List<Tag>,
    ): Timer = timers.computeIfAbsent(meterKey(name, tags)) { meterRegistry.timer(name, tags) }

    private fun meterKey(
        name: String,
        tags: List<Tag>,
    ): String = "$name|${tags.joinToString("|") { "${it.key}=${it.value}" }}"

    private fun normalizeResource(resourceName: String?): String =
        resourceName
            ?.trim()
            ?.lowercase()
            ?.ifBlank { "unknown" }
            ?: "unknown"
}
