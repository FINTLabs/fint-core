package no.fintlabs.adapter.gateway.storage

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.fintlabs.adapter.gateway.sync.FullSyncStatusStore
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.store.ResourceStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

data class WipeResult(
    val resources: Long,
    val edges: Long,
)

/**
 * Removes a whole resource type: every resource in its collection, the relation edges those
 * resources own, and its full sync status. The adapter's next full sync fills it again.
 *
 * The three steps cannot share a transaction, because Mongo does not allow dropping a collection
 * inside one. They run in an order that is safe to repeat: the edges first, then the collection,
 * then the status. When a step fails, the status is still there, so the next sweep runs all
 * three again.
 */
@Service
class ResourceWipeService(
    private val resourceStore: ResourceStore,
    private val relationEdgeStore: RelationEdgeStore,
    private val fullSyncStatusStore: FullSyncStatusStore,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun wipe(coordinate: ResourceCoordinate): WipeResult {
        val collectionName = coordinate.toCollectionName()
        val resourceType = coordinate.toResourceUri()

        val resources = resourceStore.count(null, collectionName)
        val edges = relationEdgeStore.deleteBySourceType(coordinate.toEdgeCollectionName(), resourceType)
        resourceStore.dropCollection(collectionName)
        fullSyncStatusStore.delete(coordinate)

        record(resourceType, resources, edges)
        log.info("Wiped {} resources and {} relation edges of {}", resources, edges, collectionName)

        return WipeResult(resources, edges)
    }

    private fun record(
        resourceType: String,
        resources: Long,
        edges: Long,
    ) {
        counter("fint.core.wipe.resources", resourceType).increment(resources.toDouble())
        counter("fint.core.wipe.edges", resourceType).increment(edges.toDouble())
    }

    private fun counter(
        name: String,
        resourceType: String,
    ): Counter =
        Counter
            .builder(name)
            .tag("resource", resourceType)
            .register(meterRegistry)
}
