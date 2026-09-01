package no.fintlabs.provider.storage

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.store.ResourceIdentity
import no.novari.core.shared.store.ResourceStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

data class EvictionResult(
    val resources: Long,
    val edges: Long,
)

/**
 * The delete counterpart of [ResourceWritePipeline]: takes out what a completed full sync left
 * behind, and the relation edges that pointed at or came from it.
 *
 * Staleness is a timestamp, not a list of ids. A full sync writes everything it carries at or
 * after its earliest write, so anything in the collection still older than that is something the
 * adapter no longer has. That also leaves a resource written through the event path during the
 * sync alone, because its write is newer than the threshold.
 */
@Service
class EvictionService(
    private val resourceStore: ResourceStore,
    private val relationEdgeStore: RelationEdgeStore,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Deletes everything under [coordinate] last written before [threshold], in batches.
     *
     * Edges go before the resource that owns them, and the order matters. Interrupted this way
     * around, what is left is a stale resource that has lost its back-links, which the next full
     * sync cleans up. The other way around leaves a live resource rendering a link to a resource
     * that is gone, which is the bug this exists to prevent. The cost of that order is a narrow
     * one: a client write landing between a batch's read and its delete keeps its resource, but
     * has already lost the edges it just wrote, until that resource is written again.
     *
     * The loop always ends. Every resource a batch reads is either deleted or has been written
     * since, and a written one is no longer older than [threshold], so neither can be read again.
     */
    fun evict(
        coordinate: ResourceCoordinate,
        threshold: Instant,
    ): EvictionResult {
        val collectionName = coordinate.toCollectionName()
        val edgeCollectionName = coordinate.toEdgeCollectionName()
        val resourceType = coordinate.toResourceUri()

        var resources = 0L
        var edges = 0L

        while (true) {
            val doomed = resourceStore.findIdentitiesOlderThan(threshold, BATCH_SIZE, collectionName)
            if (doomed.isEmpty()) break

            edges += deleteEdges(edgeCollectionName, resourceType, doomed)
            resources += resourceStore.deleteStaleByIds(doomed.map { it.id }, threshold, collectionName)
        }

        record(resourceType, resources, edges)
        log.info(
            "Evicted {} resources and {} relation edges from {} older than {}",
            resources,
            edges,
            collectionName,
            threshold,
        )

        return EvictionResult(resources, edges)
    }

    private fun deleteEdges(
        edgeCollectionName: String,
        resourceType: String,
        doomed: List<ResourceIdentity>,
    ): Long =
        relationEdgeStore.deleteBySources(edgeCollectionName, resourceType, doomed.map { it.id }) +
            relationEdgeStore.deleteByTargets(edgeCollectionName, resourceType, doomed.flatMap { it.identifiers })

    private fun record(
        resourceType: String,
        resources: Long,
        edges: Long,
    ) {
        counter("fint.core.eviction.resources", resourceType).increment(resources.toDouble())
        counter("fint.core.eviction.edges", resourceType).increment(edges.toDouble())
    }

    private fun counter(
        name: String,
        resourceType: String,
    ): Counter =
        Counter
            .builder(name)
            .tag("resource", resourceType)
            .register(meterRegistry)

    companion object {
        private const val BATCH_SIZE = 500
    }
}
