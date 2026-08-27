package no.novari.core.shared.store

import no.novari.core.shared.model.ResourceCoordinate
import no.novari.fint.core.model.FintResource
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.find
import org.springframework.data.mongodb.core.findById
import org.springframework.data.mongodb.core.findOne
import org.springframework.data.mongodb.core.index.Index
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

    /**
     * Gets resource by database _id field.
     */
    fun findByResourceId(
        resourceId: String,
        collectionName: String,
    ) = template.findById<ResourceEntry>(
        resourceId,
        collectionName,
    )

    /**
     * Finds a resource entry from a given collection using a specific identifier field and value.
     *
     * The method queries for a resource in the database collection by looking for a matching
     * identifier field and value combination within the `identifiers` array of the resource document.
     *
     * For example, if we have an elev with elevnummer, the id has the path /elev/elevnummer/1234.
     * This method will then query for that identifier instead of the _id field.
     *
     * @param idField The name of the identifier field to query against.
     * @param idValue The value of the identifier field to query for.
     * @param collectionName The name of the collection where the resource is stored.
     * @return The matching resource entry if found, or null if no match is found.
     */
    fun findByIdentifier(
        idField: String,
        idValue: String,
        collectionName: String,
    ): ResourceEntry? {
        val query =
            Query.query(
                Criteria.where("identifiers").elemMatch(
                    Criteria
                        .where("field")
                        .`is`(idField)
                        .and("value")
                        .`is`(idValue),
                ),
            )

        return template.findOne<ResourceEntry>(query, collectionName)
    }

    fun findAll(
        filter: Criteria?,
        collectionName: String,
    ): List<ResourceEntry> {
        val query = baseQuery(filter)
        return template.find<ResourceEntry>(query, collectionName)
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

    fun getCacheSize(coordinate: ResourceCoordinate): Long =
        template.exactCount(
            Query(),
            ResourceEntry::class.java,
            coordinate.toCollectionName(),
        )

    fun getLastUpdated(coordinate: ResourceCoordinate): Instant? {
        val collectionName = coordinate.toCollectionName()

        val query =
            Query()
                .with(Sort.by(Sort.Direction.DESC, "lastModified"))
                .limit(1)

        return template
            .findOne<ResourceEntry>(query, collectionName)
            ?.lastModified
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
