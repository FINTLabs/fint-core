package no.fintlabs.client.resource

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.BackLinkEdge
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Measures the relation edge lookups behind back-links, per resource type, so a dashboard can
 * show which types and page sizes fetch the most edges and which lookups fail.
 */
@Component
class RelationEdgeLookupMetrics(
    private val meterRegistry: MeterRegistry,
) {
    enum class Lookup(
        val tag: String,
    ) {
        PAGE("page"),
        FULL("full"),
        LIVE_READ("live_read"),
    }

    /** Runs [lookup] and records how long it took, how big it was and whether it failed. */
    fun record(
        coordinate: ResourceCoordinate,
        lookup: Lookup,
        resources: Int,
        identifiers: Int,
        block: () -> List<BackLinkEdge>,
    ): List<BackLinkEdge> {
        val tags =
            Tags.of(
                "domain",
                coordinate.domainName,
                "package",
                coordinate.packageName,
                "resource",
                coordinate.resourceName,
                "lookup",
                lookup.tag,
            )
        val start = System.nanoTime()

        val edges =
            try {
                block()
            } catch (failure: RuntimeException) {
                timer(tags.and("outcome", "error")).record(Duration.ofNanos(System.nanoTime() - start))
                throw failure
            }

        timer(tags.and("outcome", "success")).record(Duration.ofNanos(System.nanoTime() - start))
        summary("core.relation.edges.lookup.resources", "Resources in the response the edges were looked up for", tags)
            .record(resources.toDouble())
        summary(
            "core.relation.edges.lookup.identifiers",
            "Identifiers sent in the lookup",
            tags,
        ).record(identifiers.toDouble())
        summary("core.relation.edges.lookup.edges", "Edges the lookup returned", tags).record(edges.size.toDouble())

        return edges
    }

    // A few fixed buckets instead of a full histogram keeps the series count low across all resource types
    private fun timer(tags: Tags): Timer =
        Timer
            .builder("core.relation.edges.lookup")
            .description("Time spent looking up relation edges")
            .tags(tags)
            .serviceLevelObjectives(
                Duration.ofMillis(100),
                Duration.ofMillis(500),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
            ).register(meterRegistry)

    private fun summary(
        name: String,
        description: String,
        tags: Tags,
    ): DistributionSummary =
        DistributionSummary
            .builder(name)
            .description(description)
            .tags(tags)
            .serviceLevelObjectives(100.0, 1_000.0, 10_000.0, 100_000.0)
            .register(meterRegistry)
}
