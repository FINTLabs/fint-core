package no.fintlabs.adapter.gateway.storage

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.relation.RelationEdgeWrite
import no.novari.core.shared.store.Delete
import no.novari.core.shared.store.ResourceStore
import no.novari.core.shared.store.ResourceWrite
import no.novari.core.shared.store.Save
import no.novari.core.shared.store.WriteOutcome
import no.novari.core.shared.store.WriteResult
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
 * are derived only from the writes that changed stored content. A saved resource's edges are
 * replaced as a whole, so when a link is gone from the new version, the back-link it gave its
 * target is gone too. A resource delivered again with the same content keeps the edges it
 * already has.
 *
 * Every write is counted under `fint.core.resource.writes`, tagged with the resource type and
 * what the store did with it, so the share of deliveries that change nothing can be read off.
 */
@Service
class ResourceWritePipeline(
    private val resourceStore: ResourceStore,
    private val relationEdgeStore: RelationEdgeStore,
    private val transactions: MongoTransactions,
    private val meterRegistry: MeterRegistry = Metrics.globalRegistry,
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

        val outcomes =
            transactions.inTransaction {
                val outcomes = resourceStore.applyAll(ingests.map { it.toResourceWrite() })
                val edgeWrites =
                    outcomes
                        .filter { it.changedData }
                        .map { it.write.toRelationEdgeWrite(coordinates.getValue(it.write.collectionName)) }
                relationEdgeStore.applyAll(edgeWrites)
                outcomes
            }

        record(outcomes, coordinates)
    }

    private fun ResourceIngest.toResourceWrite(): ResourceWrite =
        when (this) {
            is ResourceIngest.Save -> Save(resourceId, coordinate.toCollectionName(), resource, timestamp)
            is ResourceIngest.Delete -> Delete(resourceId, coordinate.toCollectionName(), timestamp)
        }

    private fun ResourceWrite.toRelationEdgeWrite(coordinate: ResourceCoordinate): RelationEdgeWrite =
        when (this) {
            is Save -> {
                RelationEdgeWrite.Replace.of(coordinate, resourceId, resource)
            }

            is Delete -> {
                RelationEdgeWrite.Delete(
                    coordinate.toEdgeCollectionName(),
                    coordinate.toResourceUri(),
                    resourceId,
                )
            }
        }

    private fun record(
        outcomes: List<WriteOutcome>,
        coordinates: Map<String, ResourceCoordinate>,
    ) {
        outcomes
            .groupingBy { coordinates.getValue(it.write.collectionName).toResourceUri() to it.result }
            .eachCount()
            .forEach { (key, count) -> counter(key.first, key.second).increment(count.toDouble()) }
    }

    private fun counter(
        resourceType: String,
        result: WriteResult,
    ): Counter =
        Counter
            .builder(WRITES_COUNTER)
            .tag("resource", resourceType)
            .tag("result", result.name.lowercase())
            .register(meterRegistry)

    companion object {
        const val WRITES_COUNTER = "fint.core.resource.writes"
    }
}
