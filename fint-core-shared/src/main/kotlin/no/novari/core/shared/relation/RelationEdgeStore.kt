package no.novari.core.shared.relation

import no.novari.core.shared.store.IdentifierRef
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Service
class RelationEdgeStore(
    private val template: MongoTemplate,
) {
    private val indexedCollections = ConcurrentHashMap.newKeySet<String>()

    /**
     * Idempotent bulk upsert keyed by the deterministic edge id. Re-delivered or re-synced
     * batches match the existing document and change nothing; `createdAt` is written on first
     * insert only, which is why this is an [Update] and not a replace.
     */
    fun upsertAll(
        collectionName: String,
        edges: List<RelationEdge>,
    ) {
        if (edges.isEmpty()) return
        ensureIndexes(collectionName)

        val now = Instant.now()
        val bulkOps = template.bulkOps(BulkOperations.BulkMode.UNORDERED, collectionName)

        edges.associateBy { it.id }.values.forEach { edge ->
            val query = Query.query(Criteria.where("_id").`is`(edge.id))
            val update =
                Update()
                    .set("sourceIdField", edge.sourceIdField)
                    .set("sourceIdValue", edge.sourceIdValue)
                    .set("inverseName", edge.inverseName)
                    .set("targetType", edge.targetType)
                    .set("targetIdField", edge.targetIdField)
                    .set("targetIdValue", edge.targetIdValue)
                    .setOnInsert("createdAt", now)
            bulkOps.upsert(query, update)
        }

        bulkOps.execute()
    }

    /**
     * The read-time merge query: edges pointing at any of [identifiers] on [targetType].
     * Written as a rooted $or so each branch plans its own exact bounds on the
     * (targetType, targetIdField, targetIdValue) index.
     */
    fun findByTargets(
        collectionName: String,
        targetType: String,
        identifiers: Collection<IdentifierRef>,
    ): List<RelationEdge> {
        if (identifiers.isEmpty()) return emptyList()

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

        val query = Query.query(Criteria().orOperator(branches))
        return template.find(query, RelationEdge::class.java, collectionName)
    }

    /**
     * Every edge pointing at [targetType], for full-collection reads where a per-identifier
     * `$in` would be unbounded; the caller joins in memory instead.
     */
    fun findAllByTargetType(
        collectionName: String,
        targetType: String,
    ): List<RelationEdge> =
        template.find(
            Query.query(Criteria.where("targetType").`is`(targetType)),
            RelationEdge::class.java,
            collectionName,
        )

    private fun ensureIndexes(collectionName: String) {
        if (!indexedCollections.add(collectionName)) return

        template.indexOps(collectionName).createIndex(
            Index()
                .on("targetType", Sort.Direction.ASC)
                .on("targetIdField", Sort.Direction.ASC)
                .on("targetIdValue", Sort.Direction.ASC)
                .named("target_lookup"),
        )
    }
}
