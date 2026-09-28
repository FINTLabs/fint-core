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

    /**
     * Makes [edges] the whole set of edges the source owns. Each edge is inserted, or updated when
     * it is already stored, and every other edge the source owned is removed.
     */
    data class Replace(
        override val collectionName: String,
        val sourceType: String,
        val sourceId: String,
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
        val sourceType: String,
        val sourceId: String,
    ) : RelationEdgeWrite
}

@Service
class RelationEdgeStore(
    private val template: MongoTemplate,
) {
    private val indexedCollections = ConcurrentHashMap.newKeySet<String>()

    fun prepareCollection(collectionName: String) = ensureIndexes(collectionName)

    /**
     * Applies a batch of edge writes. Edges are stored in one collection per organization,
     * and one batch can hold writes for more than one organization, so the batch is grouped
     * by collection and each group is written on its own. Writing the same edge again,
     * for example after a re-sync, changes nothing: `createdAt` is only set on the first insert,
     * which is why edges are updated field by field instead of replaced as whole documents.
     */
    fun applyAll(writes: List<RelationEdgeWrite>) {
        if (writes.isEmpty()) return

        val timestamp = Instant.now()

        writes
            .groupBy { it.collectionName }
            .forEach { (collectionName, collectionWrites) ->
                ensureIndexes(collectionName)

                val bulkOps = template.bulkOps(BulkOperations.BulkMode.UNORDERED, collectionName)
                collectionWrites.forEach { bulkOps.add(it, timestamp) }
                bulkOps.execute()
            }
    }

    private fun BulkOperations.add(
        write: RelationEdgeWrite,
        timestamp: Instant,
    ): BulkOperations =
        when (write) {
            is RelationEdgeWrite.Replace -> {
                write.edges.forEach { upsert(byId(it.id), it.toUpdate(timestamp)) }
                remove(edgesOfSourceExcept(write.sourceType, write.sourceId, write.edges.map { it.id }))
            }

            is RelationEdgeWrite.Delete -> {
                remove(
                    Query.query(
                        Criteria
                            .where("sourceType")
                            .`is`(write.sourceType)
                            .and("sourceId")
                            .`is`(write.sourceId),
                    ),
                )
            }
        }

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

    fun findByTargets(
        collectionName: String,
        targetType: String,
        identifiers: Collection<IdentifierRef>,
    ): List<RelationEdge> {
        val query = targetQuery(targetType, identifiers) ?: return emptyList()
        return template.find(query, RelationEdge::class.java, collectionName)
    }

    fun findAllByTargetType(
        collectionName: String,
        targetType: String,
    ): List<RelationEdge> =
        template.find(
            Query.query(Criteria.where("targetType").`is`(targetType)),
            RelationEdge::class.java,
            collectionName,
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

    private fun byId(id: String): Query = Query.query(Criteria.where("_id").`is`(id))

    /**
     * Every edge the source owns except the ones in [keptIds]. When a source is saved, [keptIds]
     * are the edges its current links produce, so this finds the edges an earlier version left
     * behind: links the source no longer has. Removing them stops those links from still showing
     * up as back-links on their targets. With no [keptIds], it finds every edge the source owns.
     */
    private fun edgesOfSourceExcept(
        sourceType: String,
        sourceId: String,
        keptIds: Collection<String>,
    ): Query =
        Query.query(
            Criteria
                .where("sourceType")
                .`is`(sourceType)
                .and("sourceId")
                .`is`(sourceId)
                .and("_id")
                .nin(keptIds),
        )

    private fun targetQuery(
        targetType: String,
        identifiers: Collection<IdentifierRef>,
    ): Query? {
        if (identifiers.isEmpty()) return null

        val branches =
            identifiers
                .groupBy({ it.field }, { it.value })
                .map { (field, values) ->
                    Criteria
                        .where("targetType")
                        .`is`(targetType)
                        .and("targetIdField")
                        .`is`(field)
                        .and("targetIdValue")
                        .`in`(values.distinct())
                }

        return Query.query(Criteria().orOperator(branches))
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
}
