package no.fintlabs.adapter.gateway.storage

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.fintlabs.adapter.gateway.config.EvictionProperties
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.store.ResourceStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

data class EvictionResult(
    val resources: Long,
    val edges: Long,
) {
    operator fun plus(other: EvictionResult) = EvictionResult(resources + other.resources, edges + other.edges)
}

@Service
class EvictionService(
    private val resourceStore: ResourceStore,
    private val relationEdgeStore: RelationEdgeStore,
    private val transactions: MongoTransactions,
    private val meterRegistry: MeterRegistry,
    private val properties: EvictionProperties = EvictionProperties(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Removes the resources a completed full sync did not carry, together with the relation
     * edges those resources own. It happens within a transaction so they disappear together.
     */
    fun evict(
        coordinate: ResourceCoordinate,
        threshold: Instant,
    ): EvictionResult {
        val collectionName = coordinate.toCollectionName()
        val resourceType = coordinate.toResourceUri()
        val startedAt = System.nanoTime()

        resourceStore.prepareCollection(collectionName)
        relationEdgeStore.prepareCollection(coordinate.toEdgeCollectionName())

        var total = EvictionResult(0, 0)
        while (true) {
            val batch = transactions.inTransaction { evictBatch(coordinate, threshold) }
            if (batch.read == 0) break

            record(resourceType, batch.result)
            total += batch.result
            log.debug(
                "Evicted a batch of {} resources and {} relation edges from {}",
                batch.result.resources,
                batch.result.edges,
                collectionName,
            )
        }

        log.info(
            "Evicted {} resources and {} relation edges from {} older than {} in {}",
            total.resources,
            total.edges,
            collectionName,
            threshold,
            Duration.ofNanos(System.nanoTime() - startedAt),
        )

        return total
    }

    private fun evictBatch(
        coordinate: ResourceCoordinate,
        threshold: Instant,
    ): EvictionBatch {
        val collectionName = coordinate.toCollectionName()
        val ids = resourceStore.findIdsOlderThan(threshold, properties.batchSize, collectionName)
        if (ids.isEmpty()) return EvictionBatch(0, EvictionResult(0, 0))

        val edges =
            relationEdgeStore.deleteBySources(coordinate.toEdgeCollectionName(), coordinate.toResourceUri(), ids)
        val resources = resourceStore.deleteStaleByIds(ids, threshold, collectionName)

        return EvictionBatch(ids.size, EvictionResult(resources, edges))
    }

    private data class EvictionBatch(
        val read: Int,
        val result: EvictionResult,
    )

    private fun record(
        resourceType: String,
        result: EvictionResult,
    ) {
        counter("fint.core.eviction.resources", resourceType).increment(result.resources.toDouble())
        counter("fint.core.eviction.edges", resourceType).increment(result.edges.toDouble())
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
