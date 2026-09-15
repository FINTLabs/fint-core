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
 * A class that focuses on inserting and deleting resources and its related relation edges.
 * This exists because events and buffered resources has the same logic for insertion/deletion.
 */
@Service
class ResourceWritePipeline(
    private val resourceStore: ResourceStore,
    private val relationEdgeStore: RelationEdgeStore,
) {
    /**
     * Creates the indexes a later [apply] will need. Index creation is not allowed inside a
     * Mongo transaction, so a caller that applies within one must call this first, outside it.
     */
    fun prepare(coordinate: ResourceCoordinate) {
        resourceStore.prepareCollection(coordinate.toCollectionName())
        relationEdgeStore.prepareCollection(coordinate.toEdgeCollectionName())
    }

    fun apply(ingest: ResourceIngest) = applyAll(listOf(ingest))

    fun applyAll(ingests: List<ResourceIngest>) {
        val saves = ingests.filterIsInstance<ResourceIngest.Save>()
        saves.forEach { it.resource.removeSelfLinks() }

        resourceStore.applyAll(ingests.map { it.toResourceWrite() })
        relationEdgeStore.applyAll(ingests.flatMap { it.toRelationEdgeWrites() })
    }

    private fun ResourceIngest.toResourceWrite(): ResourceWrite =
        when (this) {
            is ResourceIngest.Save -> {
                Save(
                    resourceId = resourceId,
                    collectionName = coordinate.toCollectionName(),
                    resource = resource,
                    timestamp = timestamp,
                )
            }

            is ResourceIngest.Delete -> {
                Delete(
                    resourceId = resourceId,
                    collectionName = coordinate.toCollectionName(),
                    timestamp = timestamp,
                )
            }
        }

    private fun ResourceIngest.toRelationEdgeWrites(): List<RelationEdgeWrite> {
        val collectionName = coordinate.toEdgeCollectionName()

        return when (this) {
            is ResourceIngest.Save -> {
                RelationEdgeFactory
                    .createRelationEdges(coordinate, resourceId, resource)
                    .map { RelationEdgeWrite.Save(collectionName, it) }
            }

            is ResourceIngest.Delete -> {
                listOf(RelationEdgeWrite.Delete(collectionName, coordinate.toResourceUri(), resourceId))
            }
        }
    }
}
