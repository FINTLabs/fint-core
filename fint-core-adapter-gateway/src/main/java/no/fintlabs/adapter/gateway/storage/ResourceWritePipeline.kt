package no.fintlabs.adapter.gateway.storage

import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.relation.RelationEdgeWrite
import no.novari.core.shared.store.Delete
import no.novari.core.shared.store.ResourceStore
import no.novari.core.shared.store.ResourceWrite
import no.novari.core.shared.store.Save
import no.novari.fint.core.model.FintResource
import org.springframework.stereotype.Service
import java.time.Instant

sealed interface ResourceIngest {
    val coordinate: ResourceCoordinate
    val resourceId: String
    val timestamp: Instant

    data class Save(
        val resource: FintResource,
        override val coordinate: ResourceCoordinate,
        override val resourceId: String,
        override val timestamp: Instant,
    ) : ResourceIngest

    data class Delete(
        override val coordinate: ResourceCoordinate,
        override val resourceId: String,
        override val timestamp: Instant,
    ) : ResourceIngest
}

/**
 * Writes resources and the relation edges they own. Events and buffered sync records share
 * this path. One batch is one Mongo transaction. The resources are applied first, and edges
 * are derived only from the writes the store reports as taken effect. A saved resource's edges
 * are replaced as a whole, so when a link is gone from the new version, the back-link it gave
 * its target is gone too.
 */
@Service
class ResourceWritePipeline(
    private val resourceStore: ResourceStore,
    private val relationEdgeStore: RelationEdgeStore,
    private val transactions: MongoTransactions,
) {
    /**
     * Creates the indexes a later [applyAll] will need. Index creation is not allowed inside a
     * Mongo transaction. [applyAll] calls this itself before opening its transaction, so only a
     * caller that wraps the pipeline in a transaction of its own has to call it first, outside
     * that transaction.
     */
    fun prepare(coordinate: ResourceCoordinate) {
        resourceStore.prepareCollection(coordinate.toCollectionName())
        relationEdgeStore.prepareCollection(coordinate.toEdgeCollectionName())
    }

    fun apply(ingest: ResourceIngest) = applyAll(listOf(ingest))

    fun applyAll(ingests: List<ResourceIngest>) {
        if (ingests.isEmpty()) return

        val coordinates = ingests.associateBy({ it.coordinate.toCollectionName() }, { it.coordinate })
        coordinates.values.forEach(::prepare)
        ingests.filterIsInstance<ResourceIngest.Save>().forEach { it.resource.removeSelfLinks() }

        transactions.inTransaction {
            val effective = resourceStore.applyAll(ingests.map { it.toResourceWrite() })
            val edgeWrites = effective.map { it.toRelationEdgeWrite(coordinates.getValue(it.collectionName)) }
            relationEdgeStore.applyAll(edgeWrites)
        }
    }

    private fun ResourceIngest.toResourceWrite(): ResourceWrite =
        when (this) {
            is ResourceIngest.Save -> Save(resourceId, coordinate.toCollectionName(), resource, timestamp)
            is ResourceIngest.Delete -> Delete(resourceId, coordinate.toCollectionName(), timestamp)
        }

    private fun ResourceWrite.toRelationEdgeWrite(coordinate: ResourceCoordinate): RelationEdgeWrite =
        when (this) {
            is Save -> RelationEdgeWrite.Replace.of(coordinate, resourceId, resource)
            is Delete -> RelationEdgeWrite.Delete(coordinate.toEdgeCollectionName(), coordinate.toResourceUri(), resourceId)
        }
}
