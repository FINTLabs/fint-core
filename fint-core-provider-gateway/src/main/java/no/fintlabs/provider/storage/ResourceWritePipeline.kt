package no.fintlabs.provider.storage

import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.RelationEdgeFactory
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
 * are derived only from the writes the store reports as taken effect.
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
            val edgeWrites = effective.flatMap { it.toRelationEdgeWrites(coordinates.getValue(it.collectionName)) }
            relationEdgeStore.applyAll(edgeWrites)
        }
    }

    private fun ResourceIngest.toResourceWrite(): ResourceWrite =
        when (this) {
            is ResourceIngest.Save -> Save(resourceId, coordinate.toCollectionName(), resource, timestamp)
            is ResourceIngest.Delete -> Delete(resourceId, coordinate.toCollectionName(), timestamp)
        }

    private fun ResourceWrite.toRelationEdgeWrites(coordinate: ResourceCoordinate): List<RelationEdgeWrite> {
        val collectionName = coordinate.toEdgeCollectionName()

        return when (this) {
            is Save -> {
                RelationEdgeFactory
                    .createRelationEdges(coordinate, resourceId, resource)
                    .map { RelationEdgeWrite.Save(collectionName, it) }
            }

            is Delete -> {
                listOf(RelationEdgeWrite.Delete(collectionName, coordinate.toResourceUri(), resourceId))
            }
        }
    }
}
