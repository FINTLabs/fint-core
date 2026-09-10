package no.fintlabs.provider.storage

import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.RelationEdgeFactory
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.relation.RelationEdgeWrite
import no.novari.core.shared.store.ResourceStore
import no.novari.core.shared.store.ResourceWrite
import no.novari.fint.core.model.FintResource
import org.springframework.stereotype.Service
import java.time.Instant

data class ResourceIngest(
    val coordinate: ResourceCoordinate,
    val resourceId: String,
    val resource: FintResource?,
    val timestamp: Instant,
)

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
        if (ingests.isEmpty()) return

        ingests.forEach { it.resource.removeSelfLinks() }
        resourceStore.saveAll(ingests.toResourceWrites())
        relationEdgeStore.saveAll(ingests.toRelationEdgeWrites())
    }

    private fun List<ResourceIngest>.toResourceWrites() =
        map {
            ResourceWrite(
                resourceId = it.resourceId,
                collectionName = it.coordinate.toCollectionName(),
                resource = it.resource,
                timestamp = it.timestamp,
            )
        }

    private fun List<ResourceIngest>.toRelationEdgeWrites() =
        flatMap { ingest ->
            RelationEdgeFactory
                .createRelationEdges(ingest.coordinate, ingest.resourceId, ingest.resource)
                .map { RelationEdgeWrite(ingest.coordinate.toEdgeCollectionName(), it) }
        }
}
