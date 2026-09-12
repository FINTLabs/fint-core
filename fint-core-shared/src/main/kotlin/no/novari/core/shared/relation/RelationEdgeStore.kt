package no.novari.core.shared.relation

import no.novari.core.shared.store.IdentifierRef
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

    data class Save(
        override val collectionName: String,
        val edge: RelationEdge,
    ) : RelationEdgeWrite

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
     * Inserts or updates a batch of edges, matched by id. Edges are stored in one collection per
     * organization (the org is part of the collection name),
     * and one batch can contain edges for more than one organization, so this groups the batch
     * by collection and writes each group separately. Writing the same edge again, for example
     * after a re-sync, has no extra effect: `createdAt` is only set on the first insert, which is
     * why this uses [Update] instead of replacing the whole document.
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
            is RelationEdgeWrite.Save -> {
                upsert(Query.query(Criteria.where("_id").`is`(write.edge.id)), write.edge.toUpdate(timestamp))
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

    fun deleteByTargets(
        collectionName: String,
        targetType: String,
        identifiers: Collection<IdentifierRef>,
    ): Long {
        val query = targetQuery(targetType, identifiers) ?: return 0

        ensureIndexes(collectionName)

        return template.remove(query, collectionName).deletedCount
    }

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
