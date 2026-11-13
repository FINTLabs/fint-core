package no.novari.core.shared.relation

import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.IdentifierRef
import no.novari.fint.core.model.FintResource
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.UpdateDefinition
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

sealed interface RelationEdgeWrite {
    val collectionName: String
    val sourceType: String
    val sourceId: String

    /**
     * Makes [edges] the whole set of edges the source owns. Each edge is inserted, or updated when
     * it is already stored, and every other edge the source owned is removed.
     */
    data class Replace(
        override val collectionName: String,
        override val sourceType: String,
        override val sourceId: String,
        val edges: List<RelationEdge>,
    ) : RelationEdgeWrite {
        companion object {
            /** The edges [resource] owns right now, derived from its links, as one replace. */
            fun of(
                coordinate: ResourceCoordinate,
                resourceId: String,
                resource: FintResource,
            ): Replace =
                Replace(
                    collectionName = coordinate.toEdgeCollectionName(),
                    sourceType = coordinate.toResourceUri(),
                    sourceId = resourceId,
                    edges = RelationEdgeFactory.createRelationEdges(coordinate, resourceId, resource),
                )
        }
    }

    data class Delete(
        override val collectionName: String,
        override val sourceType: String,
        override val sourceId: String,
    ) : RelationEdgeWrite
}

/**
 * The edges a batch of writes [added] and [removed]. These are the back-links their targets got
 * and lost. An edge that is stored again with another source identifier or back-relation is in
 * both, the stored one as removed and the new one as added.
 */
data class RelationEdgeChanges(
    val added: List<RelationEdge>,
    val removed: List<RelationEdge>,
) {
    /** How many edges were stored, counting new ones and ones stored again with other content. */
    val written: Long get() = added.size.toLong()

    /** The removed edges that are gone for good, so without the ones stored again with other content. */
    val gone: List<RelationEdge>
        get() {
            val storedAgain = added.mapTo(HashSet()) { it.id }
            return removed.filterNot { it.id in storedAgain }
        }

    /** How many edges are gone for good. */
    val deleted: Long get() = gone.size.toLong()

    operator fun plus(other: RelationEdgeChanges): RelationEdgeChanges = RelationEdgeChanges(added + other.added, removed + other.removed)

    companion object {
        val NONE = RelationEdgeChanges(emptyList(), emptyList())
    }
}

@Service
class RelationEdgeStore(
    private val template: MongoTemplate,
) {
    private val indexedCollections = ConcurrentHashMap.newKeySet<String>()

    fun prepareCollection(collectionName: String) = ensureIndexes(collectionName)

    /**
     * Applies a batch of edge writes and says which edges it added and removed. Edges are stored
     * in one collection per organization, and one batch can hold writes for more than one
     * organization, so the batch is grouped by collection and each group is written on its own.
     *
     * The store reads the edges the sources own first and only writes what differs, so writing
     * the same edges again, for example after a re-sync, writes nothing. The answer is only
     * trustworthy inside a Mongo transaction.
     */
    fun applyAll(writes: List<RelationEdgeWrite>): RelationEdgeChanges {
        if (writes.isEmpty()) return RelationEdgeChanges.NONE

        val timestamp = Instant.now()

        return writes
            .groupBy { it.collectionName }
            .map { (collectionName, collectionWrites) ->
                ensureIndexes(collectionName)
                applyToCollection(collectionName, collectionWrites, timestamp)
            }.fold(RelationEdgeChanges.NONE, RelationEdgeChanges::plus)
    }

    private fun applyToCollection(
        collectionName: String,
        writes: List<RelationEdgeWrite>,
        timestamp: Instant,
    ): RelationEdgeChanges {
        val changes = changesOf(writes, storedEdgesOf(collectionName, writes))
        if (changes.added.isEmpty() && changes.removed.isEmpty()) return changes

        val bulkOps = template.bulkOps(BulkOperations.BulkMode.UNORDERED, collectionName)
        // An upsert and not an insert, since an edge stored again with other content is already there.
        changes.added.forEach { bulkOps.upsert(byId(it.id), it.toUpdate(timestamp)) }
        val gone = changes.gone.map { it.id }
        if (gone.isNotEmpty()) bulkOps.remove(Query.query(Criteria.where("_id").`in`(gone)))
        bulkOps.execute()

        return changes
    }

    private fun storedEdgesOf(
        collectionName: String,
        writes: List<RelationEdgeWrite>,
    ): List<RelationEdge> =
        writes
            .groupBy({ it.sourceType }, { it.sourceId })
            .flatMap { (sourceType, sourceIds) -> findBySources(collectionName, sourceType, sourceIds) }

    /** What [writes] change when [stored] holds every edge their sources own now. */
    private fun changesOf(
        writes: List<RelationEdgeWrite>,
        stored: List<RelationEdge>,
    ): RelationEdgeChanges {
        val storedById = stored.associateBy { it.id }
        // A delete wants no edges, so every edge its source owns ends up as removed.
        val wantedById = writes.filterIsInstance<RelationEdgeWrite.Replace>().flatMap { it.edges }.associateBy { it.id }

        return RelationEdgeChanges(
            added = wantedById.values.filterNot { it.rendersSameLinkAs(storedById[it.id]) },
            removed = stored.filterNot { it.rendersSameLinkAs(wantedById[it.id]) },
        )
    }

    /**
     * True when [stored] gives its target the same back-link as this edge. Source and target are
     * part of the edge id, so two edges with the same id can only differ in what the link looks like.
     */
    private fun RelationEdge.rendersSameLinkAs(stored: RelationEdge?): Boolean =
        stored != null &&
            sourceIdField == stored.sourceIdField &&
            sourceIdValue == stored.sourceIdValue &&
            inverseName == stored.inverseName

    private fun RelationEdge.toUpdate(timestamp: Instant): UpdateDefinition =
        Update()
            .set("sourceType", sourceType)
            .set("sourceId", sourceId)
            .set("sourceIdField", sourceIdField)
            .set("sourceIdValue", sourceIdValue)
            .set("inverseName", inverseName)
            .set("targetType", targetType)
            .set("targetIdField", targetIdField)
            .set("targetIdValue", targetIdValue)
            .setOnInsert("createdAt", timestamp)

    /**
     * The back-link parts of the edges of [targetType] that point at any of [identifiers]. The
     * lookup runs as small queries one after the other, one identifier field and at most
     * [TARGET_VALUES_PER_QUERY] values each.
     */
    fun findByTargets(
        collectionName: String,
        targetType: String,
        identifiers: Collection<IdentifierRef>,
    ): List<BackLinkEdge> =
        identifiers
            .groupBy({ it.field }, { it.value })
            .flatMap { (field, values) ->
                // An edge has one target field and one target value, so no edge comes back from two queries
                values.distinct().chunked(TARGET_VALUES_PER_QUERY).flatMap { chunk ->
                    template.find(targetQuery(targetType, field, chunk), StoredBackLinkEdge::class.java, collectionName)
                }
            }

    /** The edges of [sourceType] owned by any of [sourceIds]. */
    fun findBySources(
        collectionName: String,
        sourceType: String,
        sourceIds: Collection<String>,
    ): List<RelationEdge> {
        if (sourceIds.isEmpty()) return emptyList()

        val query =
            Query.query(
                Criteria
                    .where("sourceType")
                    .`is`(sourceType)
                    .and("sourceId")
                    .`in`(sourceIds.distinct()),
            )

        return template.find(query, RelationEdge::class.java, collectionName)
    }

    /** The ids of every source of [sourceType] that owns at least one edge in [collectionName]. */
    fun findSourceIds(
        collectionName: String,
        sourceType: String,
    ): List<String> =
        template.findDistinct(
            Query.query(Criteria.where("sourceType").`is`(sourceType)),
            "sourceId",
            collectionName,
            String::class.java,
        )

    fun deleteBySources(
        collectionName: String,
        sourceType: String,
        sourceIds: Collection<String>,
    ): Long {
        if (sourceIds.isEmpty()) return 0

        ensureIndexes(collectionName)

        val query =
            Query.query(
                Criteria
                    .where("sourceType")
                    .`is`(sourceType)
                    .and("sourceId")
                    .`in`(sourceIds.distinct()),
            )

        return template.remove(query, collectionName).deletedCount
    }

    /**
     * Removes every edge that resources of one type own, for example every edge the Elevforhold
     * resources of an org declared.
     */
    fun deleteBySourceType(
        collectionName: String,
        sourceType: String,
    ): Long {
        ensureIndexes(collectionName)

        val query = Query.query(Criteria.where("sourceType").`is`(sourceType))

        return template.remove(query, collectionName).deletedCount
    }

    private fun byId(id: String): Query = Query.query(Criteria.where("_id").`is`(id))

    private fun targetQuery(
        targetType: String,
        field: String,
        values: List<String>,
    ): Query =
        Query
            .query(
                Criteria
                    .where("targetType")
                    .`is`(targetType)
                    .and("targetIdField")
                    .`is`(field)
                    .and("targetIdValue")
                    .`in`(values),
            ).apply {
                fields()
                    .include("sourceIdField", "sourceIdValue", "inverseName", "targetIdField", "targetIdValue")
                    .exclude("_id")
            }

    private fun ensureIndexes(collectionName: String) {
        if (!indexedCollections.add(collectionName)) return

        template.indexOps(collectionName).createIndex(
            Index()
                .on("targetType", Sort.Direction.ASC)
                .on("targetIdField", Sort.Direction.ASC)
                .on("targetIdValue", Sort.Direction.ASC)
                .named("target_lookup"),
        )

        template.indexOps(collectionName).createIndex(
            Index()
                .on("sourceType", Sort.Direction.ASC)
                .on("sourceId", Sort.Direction.ASC)
                .named("source_lookup"),
        )
    }

    companion object {
        // Keeps each query and its result small on a small Cosmos DB node
        const val TARGET_VALUES_PER_QUERY = 500
    }
}

internal data class StoredBackLinkEdge(
    override val sourceIdField: String,
    override val sourceIdValue: String,
    override val inverseName: String,
    override val targetIdField: String,
    override val targetIdValue: String,
) : BackLinkEdge
