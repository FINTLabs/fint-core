package no.fintlabs.client.resource

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.fintlabs.client.resource.RelationEdgeLookupMetrics.Lookup
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.RelationEdge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RelationEdgeLookupMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = RelationEdgeLookupMetrics(registry)
    private val coordinate = ResourceCoordinate("fintlabs.no", "utdanning", "elev", "skole")

    @Test
    fun `a lookup records its size and time per resource type`() {
        val edges = listOf(edge("1"), edge("2"), edge("3"))

        val result = metrics.record(coordinate, Lookup.PAGE, resources = 2, identifiers = 5) { edges }

        assertEquals(edges, result)
        assertEquals(5.0, summary("core.relation.edges.lookup.identifiers").totalAmount())
        assertEquals(3.0, summary("core.relation.edges.lookup.edges").totalAmount())
        assertEquals(2.0, summary("core.relation.edges.lookup.resources").totalAmount())
        assertEquals(1, timer("success").count())
    }

    @Test
    fun `a failed lookup is counted as an error and rethrown`() {
        assertThrows<IllegalStateException> {
            metrics.record(
                coordinate,
                Lookup.PAGE,
                resources = 2,
                identifiers = 5,
            ) { throw IllegalStateException("out of memory") }
        }

        assertEquals(1, timer("error").count())
        assertEquals(null, registry.find("core.relation.edges.lookup.edges").summary())
    }

    private fun summary(name: String) =
        registry
            .get(name)
            .tags("domain", "utdanning", "package", "elev", "resource", "skole", "lookup", "page")
            .summary()

    private fun timer(outcome: String) =
        registry
            .get("core.relation.edges.lookup")
            .tags("resource", "skole", "lookup", "page", "outcome", outcome)
            .timer()

    private fun edge(id: String) =
        RelationEdge(
            id = id,
            sourceType = "utdanning/elev/elevforhold",
            sourceId = id,
            sourceIdField = "systemid",
            sourceIdValue = id,
            inverseName = "elevforhold",
            targetType = "utdanning/elev/skole",
            targetIdField = "systemid",
            targetIdValue = "S-1",
        )
}
