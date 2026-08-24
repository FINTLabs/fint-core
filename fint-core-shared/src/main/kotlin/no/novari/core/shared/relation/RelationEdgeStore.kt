package no.novari.core.shared.relation

import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service

@Service
class RelationEdgeStore(
    private val mongoTemplate: MongoTemplate,
) {
    fun saveAll(edges: List<StoredRelation>) {
        if (edges.isEmpty()) return

        mongoTemplate
            .bulkOps(BulkOperations.BulkMode.UNORDERED, StoredRelation::class.java)
            .insert(edges)
            .execute()
    }
}
