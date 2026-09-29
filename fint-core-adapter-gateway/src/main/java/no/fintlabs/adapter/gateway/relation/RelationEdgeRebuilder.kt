package no.fintlabs.adapter.gateway.relation

import no.fintlabs.adapter.gateway.storage.MongoTransactions
import no.fintlabs.adapter.gateway.storage.ResourceWritePipeline
import no.novari.core.shared.json.FintJson
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.model.toResourceClass
import no.novari.core.shared.relation.RelationEdge
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.relation.RelationEdgeWrite
import no.novari.core.shared.store.PageAnchor
import no.novari.core.shared.store.ResourceEntry
import no.novari.core.shared.store.ResourceStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Rebuilds the relation edges of one resource type for one organization from the resources stored
 * right now, without waiting for a full sync, and can first report what a rebuild would change.
 *
 * The stored resources are read [batchSize] at a time, in the order [ResourceStore.findPageAfter]
 * pages through them, so every resource is read once. A rebuild replaces, for each
 * batch in one Mongo transaction, every resource's edges with the ones its current links produce:
 * missing edges are added, and edges its earlier versions left behind are removed. After the last
 * batch, the edges of sources that are no longer stored are removed too. A sync write that lands
 * on the same source at the same time writes the same edges in its own transaction, so Mongo
 * reports a conflict and one of the two runs again.
 *
 * Both can take minutes for a large type, so callers run them through [RelationEdgeJobs], which
 * also makes sure only one runs at a time.
 */
@Service
class RelationEdgeRebuilder(
    private val resourceStore: ResourceStore,
    private val relationEdgeStore: RelationEdgeStore,
    private val resourceWritePipeline: ResourceWritePipeline,
    private val transactions: MongoTransactions,
    @param:Value("\${fint.relation-edges.rebuild-batch-size:500}") private val batchSize: Int,
) {
    private val storageMapper = FintJson.storageMapper()

    /** Rebuilds the edges owned by the resources at [coordinate] and says what it did. */
    fun rebuild(coordinate: ResourceCoordinate): RelationEdgeRebuild =
        replaceEdgesOfStoredSources(coordinate) + removeEdgesOfSourcesGone(coordinate)

    /**
     * Reports what [rebuild] would change for [coordinate], without writing anything. The edges
     * are derived the same way a rebuild derives them and compared with the ones stored.
     *
     * To check a rebuild: run this, then [rebuild], then this again. The rebuild removes
     * [RelationEdgeDrift.edgesStale] plus [RelationEdgeDrift.edgesOfSourcesGone] edges and writes
     * at least [RelationEdgeDrift.edgesMissing]; it writes more only when an edge kept its id but
     * changed a field. The second report then finds nothing. Syncs that write in between can make
     * the numbers differ by the edges they touched.
     */
    fun drift(coordinate: ResourceCoordinate): RelationEdgeDrift = driftOfStoredSources(coordinate) + driftOfSourcesGone(coordinate)

    private fun replaceEdgesOfStoredSources(coordinate: ResourceCoordinate): RelationEdgeRebuild {
        resourceWritePipeline.prepare(coordinate)

        var total = RelationEdgeRebuild.NONE
        var anchor: PageAnchor? = null
        do {
            val batch =
                transactions.inTransaction { replaceEdgesOf(storedResourcesAfter(anchor, coordinate), coordinate) }
            total += batch.rebuild
            anchor = batch.last
        } while (batch.size == batchSize)

        return total
    }

    private fun replaceEdgesOf(
        entries: List<ResourceEntry>,
        coordinate: ResourceCoordinate,
    ): RebuiltBatch {
        val written = relationEdgeStore.applyAll(replacesOf(entries, coordinate))
        return RebuiltBatch(
            size = entries.size,
            last = entries.lastOrNull()?.toAnchor(),
            rebuild = RelationEdgeRebuild(entries.size.toLong(), written.written, written.removed),
        )
    }

    private fun removeEdgesOfSourcesGone(coordinate: ResourceCoordinate): RelationEdgeRebuild {
        val removed =
            sourceIdsWithEdges(coordinate).chunked(batchSize).sumOf { sourceIds ->
                transactions.inTransaction {
                    deleteEdgesOwnedBy(
                        sourcesNoLongerStored(sourceIds, coordinate),
                        coordinate,
                    )
                }
            }
        return RelationEdgeRebuild(resourcesRead = 0, edgesWritten = 0, edgesRemoved = removed)
    }

    private fun driftOfStoredSources(coordinate: ResourceCoordinate): RelationEdgeDrift {
        var drift = RelationEdgeDrift.NONE
        var anchor: PageAnchor? = null
        do {
            val entries = storedResourcesAfter(anchor, coordinate)
            drift += driftOf(entries, coordinate)
            anchor = entries.lastOrNull()?.toAnchor()
        } while (entries.size == batchSize)

        return drift
    }

    private fun driftOf(
        entries: List<ResourceEntry>,
        coordinate: ResourceCoordinate,
    ): RelationEdgeDrift {
        val produced = edgesProducedBy(entries, coordinate)
        val stored = edgesOwnedBy(entries.map { it.id }, coordinate)
        return RelationEdgeDrift.of(
            resourcesRead = entries.size,
            missing = produced.notIn(stored),
            stale = stored.notIn(produced),
        )
    }

    private fun driftOfSourcesGone(coordinate: ResourceCoordinate): RelationEdgeDrift =
        sourceIdsWithEdges(coordinate)
            .chunked(batchSize)
            .map { sourceIds ->
                RelationEdgeDrift.ofSourcesGone(
                    edgesOwnedBy(
                        sourcesNoLongerStored(
                            sourceIds,
                            coordinate,
                        ),
                        coordinate,
                    ),
                )
            }.fold(RelationEdgeDrift.NONE, RelationEdgeDrift::plus)

    private fun storedResourcesAfter(
        anchor: PageAnchor?,
        coordinate: ResourceCoordinate,
    ): List<ResourceEntry> =
        resourceStore.findPageAfter(
            anchor,
            filter = null,
            size = batchSize,
            collectionName = coordinate.toCollectionName(),
        )

    private fun sourcesNoLongerStored(
        sourceIds: List<String>,
        coordinate: ResourceCoordinate,
    ): List<String> {
        val stored = resourceStore.findStoredIds(sourceIds, coordinate.toCollectionName())
        return sourceIds.filterNot { it in stored }
    }

    private fun replacesOf(
        entries: List<ResourceEntry>,
        coordinate: ResourceCoordinate,
    ): List<RelationEdgeWrite.Replace> {
        val resourceClass = coordinate.toResourceClass()
        return entries.map {
            RelationEdgeWrite.Replace.of(
                coordinate,
                it.id,
                storageMapper.convertValue(it.data, resourceClass),
            )
        }
    }

    private fun edgesProducedBy(
        entries: List<ResourceEntry>,
        coordinate: ResourceCoordinate,
    ): List<RelationEdge> = replacesOf(entries, coordinate).flatMap { it.edges }.distinctBy { it.id }

    private fun edgesOwnedBy(
        sourceIds: List<String>,
        coordinate: ResourceCoordinate,
    ): List<RelationEdge> = relationEdgeStore.findBySources(coordinate.toEdgeCollectionName(), coordinate.toResourceUri(), sourceIds)

    private fun sourceIdsWithEdges(coordinate: ResourceCoordinate): List<String> =
        relationEdgeStore.findSourceIds(coordinate.toEdgeCollectionName(), coordinate.toResourceUri())

    private fun deleteEdgesOwnedBy(
        sourceIds: List<String>,
        coordinate: ResourceCoordinate,
    ): Long = relationEdgeStore.deleteBySources(coordinate.toEdgeCollectionName(), coordinate.toResourceUri(), sourceIds)

    private fun List<RelationEdge>.notIn(others: List<RelationEdge>): List<RelationEdge> {
        val otherIds = others.mapTo(HashSet()) { it.id }
        return filterNot { it.id in otherIds }
    }

    private fun ResourceEntry.toAnchor(): PageAnchor = PageAnchor(createdAt, id)

    private data class RebuiltBatch(
        val size: Int,
        val last: PageAnchor?,
        val rebuild: RelationEdgeRebuild,
    )
}

/**
 * What a rebuild did: how many stored resources it read, how many edges it inserted or changed,
 * and how many it removed.
 */
data class RelationEdgeRebuild(
    val resourcesRead: Long,
    val edgesWritten: Long,
    val edgesRemoved: Long,
) : RelationEdgeJobResult {
    operator fun plus(other: RelationEdgeRebuild): RelationEdgeRebuild =
        RelationEdgeRebuild(
            resourcesRead = resourcesRead + other.resourcesRead,
            edgesWritten = edgesWritten + other.edgesWritten,
            edgesRemoved = edgesRemoved + other.edgesRemoved,
        )

    companion object {
        val NONE = RelationEdgeRebuild(0, 0, 0)
    }
}

/**
 * What a rebuild would change: edges the stored resources should own but do not, edges they own
 * that their current links no longer produce, and edges whose source is no longer stored, with up
 * to [EXAMPLES_PER_KIND] examples of each.
 */
data class RelationEdgeDrift(
    val resourcesRead: Long,
    val edgesMissing: Long,
    val edgesStale: Long,
    val edgesOfSourcesGone: Long,
    val examples: List<RelationEdgeDriftExample>,
) : RelationEdgeJobResult {
    operator fun plus(other: RelationEdgeDrift): RelationEdgeDrift =
        RelationEdgeDrift(
            resourcesRead = resourcesRead + other.resourcesRead,
            edgesMissing = edgesMissing + other.edgesMissing,
            edgesStale = edgesStale + other.edgesStale,
            edgesOfSourcesGone = edgesOfSourcesGone + other.edgesOfSourcesGone,
            examples =
                (examples + other.examples)
                    .groupBy { it.kind }
                    .flatMap { (_, sameKind) -> sameKind.take(EXAMPLES_PER_KIND) },
        )

    companion object {
        const val EXAMPLES_PER_KIND = 10
        val NONE = RelationEdgeDrift(0, 0, 0, 0, emptyList())

        fun of(
            resourcesRead: Int,
            missing: List<RelationEdge>,
            stale: List<RelationEdge>,
        ): RelationEdgeDrift =
            RelationEdgeDrift(
                resourcesRead = resourcesRead.toLong(),
                edgesMissing = missing.size.toLong(),
                edgesStale = stale.size.toLong(),
                edgesOfSourcesGone = 0,
                examples =
                    missing
                        .take(EXAMPLES_PER_KIND)
                        .map { RelationEdgeDriftExample.of(RelationEdgeDriftExample.Kind.MISSING, it) } +
                        stale
                            .take(
                                EXAMPLES_PER_KIND,
                            ).map { RelationEdgeDriftExample.of(RelationEdgeDriftExample.Kind.STALE, it) },
            )

        fun ofSourcesGone(edges: List<RelationEdge>): RelationEdgeDrift =
            RelationEdgeDrift(
                resourcesRead = 0,
                edgesMissing = 0,
                edgesStale = 0,
                edgesOfSourcesGone = edges.size.toLong(),
                examples =
                    edges
                        .take(EXAMPLES_PER_KIND)
                        .map { RelationEdgeDriftExample.of(RelationEdgeDriftExample.Kind.SOURCE_GONE, it) },
            )
    }
}

/** One edge a rebuild would add or remove: which source owns it and which back-link it gives which target. */
data class RelationEdgeDriftExample(
    val kind: Kind,
    val sourceId: String,
    val inverseName: String,
    val targetType: String,
    val targetIdField: String,
    val targetIdValue: String,
) {
    enum class Kind { MISSING, STALE, SOURCE_GONE }

    companion object {
        fun of(
            kind: Kind,
            edge: RelationEdge,
        ): RelationEdgeDriftExample =
            RelationEdgeDriftExample(
                kind = kind,
                sourceId = edge.sourceId,
                inverseName = edge.inverseName,
                targetType = edge.targetType,
                targetIdField = edge.targetIdField,
                targetIdValue = edge.targetIdValue,
            )
    }
}
