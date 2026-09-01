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

@Service
class EvictionService(
    private val resourceStore: ResourceStore,
    private val relationEdgeStore: RelationEdgeStore,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
