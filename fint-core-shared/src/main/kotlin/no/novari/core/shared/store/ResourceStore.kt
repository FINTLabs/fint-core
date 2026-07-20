package no.novari.core.shared.store

import no.novari.fint.model.resource.FintResource
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.find
import org.springframework.data.mongodb.core.findById
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ResourceStore(
    private val template: MongoTemplate,
    private val bsonConverter: FintResourceBsonConverter,
) {
    // Save single Resource
    fun save(
        resourceId: String,
        collectionName: String,
        resource: FintResource,
        timestamp: Instant,
    ) {
        saveAll(
            listOf(
                ResourceWrite(
                    resourceId = resourceId,
                    collectionName = collectionName,
                    resource = resource,
                    timestamp = timestamp,
                ),
            ),
        )
    }

    // Builds and executes a bulk upsert operation per collection.
    fun saveAll(writes: List<ResourceWrite>) {
        if (writes.isEmpty()) return

        writes
            // Bulk operations are collection-specific, so split the incoming writes by target collection.
            .groupBy { it.collectionName }
            .forEach { (collectionName, collectionWrites) ->
                // If the same resource appears multiple times in this batch, this keeps the last
                val latestWritesById = collectionWrites.associateBy { it.resourceId }

                // Use unordered bulk writes so MongoDB can apply independent upserts, which may be faster.
                val bulkOps =
                    template.bulkOps(
                        BulkOperations.BulkMode.UNORDERED,
                        collectionName,
                    )

                latestWritesById.values.forEach { write ->
                    // Match the Mongo document by resource id, which is stored as the document _id.
                    val query = Query.query(Criteria.where("_id").`is`(write.resourceId))

                    // Replace the resource payload and lookup fields, while preserving createdAt for existing documents.
                    val update =
                        Update()
                            .set("data", bsonConverter.toDocument(write.resource))
                            .set("identifiers", write.resource.toIdentifierRefs())
                            .set("lastModified", write.timestamp)
                            .setOnInsert("createdAt", write.timestamp)

                    // Insert a new document or update the existing document for this resource id.
                    bulkOps.upsert(query, update)
                }

                bulkOps.execute()
            }
    }

    fun findByResourceId(
        resourceId: String,
        collectionName: String,
    ) = template.findById<ResourceEntry>(
        resourceId,
        collectionName,
    )

    fun findAll(
        filter: Criteria?,
        collectionName: String,
    ) {
        val query = baseQuery(filter)
        template.find<ResourceEntry>(query, collectionName) // TODO: Check if this actually returns all
    }

    fun findPage(
        filter: Criteria?,
        size: Int,
        offset: Long,
        collectionName: String,
    ): List<ResourceEntry> {
        val query = baseQuery(filter)
        val pageQuery = Query.of(query).skip(offset).limit(size)

        return template.find<ResourceEntry>(pageQuery, collectionName)
    }

    /**
     * Constructs a base query object for MongoDB operations.
     *
     * The query is initialized optionally with filtering criteria and is sorted in ascending
     * order by `createdAt` and `_id`.
     *
     * In other words, since the controller takes a sinceTimeStamp, we have to account for it here. So we insert
     * it as base for each query.
     *
     * @param filter optional filtering criteria to be applied to the query. If null, no criteria are added.
     * @return a query object with the applied criteria and sorting.
     */
    private fun baseQuery(filter: Criteria?): Query =
        Query().apply {
            filter?.let { addCriteria(it) }
            with(Sort.by(Sort.Direction.ASC, "createdAt", "_id"))
        }
}
