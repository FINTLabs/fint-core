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

data class RelationEdgeWrite(
    val collectionName: String,
    val edge: RelationEdge,
)

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
    fun saveAll(writes: List<RelationEdgeWrite>) {
        if (writes.isEmpty()) return

        val now = Instant.now()

        writes
            .groupBy { it.collectionName }
            .forEach { (collectionName, collectionWrites) ->
                ensureIndexes(collectionName)

                val bulkOps = template.bulkOps(BulkOperations.BulkMode.UNORDERED, collectionName)

                collectionWrites
                    .map { it.edge }
                    .associateBy { it.id }
                    .values
                    .forEach { edge ->
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
    }

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
