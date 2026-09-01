package no.novari.core.shared.store

import no.novari.core.shared.model.ResourceCoordinate
import no.novari.fint.core.model.FintResource
import org.bson.Document
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.AggregationOperation
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate
import org.springframework.data.mongodb.core.find
import org.springframework.data.mongodb.core.findById
import org.springframework.data.mongodb.core.findOne
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Date

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

    /**
     * Guarded bulk upsert per collection: a write only applies when its timestamp is not older
     * than the stored document's lastModified, so a lagging writer (a deep sync buffer backlog)
     * can never revert a fresher write (a direct event write) for the same resource. Equal
     * timestamps let the incoming write win. createdAt is preserved for existing documents.
     */
    fun saveAll(writes: List<ResourceWrite>) {
        if (writes.isEmpty()) return

        writes
            .groupBy { it.collectionName }
            .forEach { (collectionName, collectionWrites) ->
                val latestWritesById = collectionWrites.associateBy { it.resourceId }

                val bulkOps =
                    template.bulkOps(
                        BulkOperations.BulkMode.UNORDERED,
                        collectionName,
                    )

                latestWritesById.values.forEach { write ->
                    val query = Query.query(Criteria.where("_id").`is`(write.resourceId))
                    bulkOps.upsert(query, write.toGuardedUpdate())
                }

                bulkOps.execute()
            }
    }

    /**
     * The staleness check lives inside the update expression, not in the query, because of how
     * upsert works: query matched means update, no match means insert, nothing else. A timestamp
     * clause in the query would make a stale write look like "no match", so upsert would try to
     * insert a duplicate _id and throw. Instead the query matches on _id alone and every field
     * uses $cond to either keep its stored value or take the incoming one, so a stale write is a
     * clean no-op instead of an error.
     *
     * All guarded fields share the exact same condition through keepUnlessStale on purpose:
     * one shared condition means the write applies fully or not at all, never a half-updated
     * document.
     */
    private fun ResourceWrite.toGuardedUpdate(): AggregationUpdate {
        val incomingTimestamp = Date.from(timestamp)
        val identifierDocuments =
            resource.toIdentifierRefs().map { Document("field", it.field).append("value", it.value) }

        fun keepUnlessStale(
            field: String,
            incoming: Any,
        ): Document =
            Document(
                "\$cond",
                listOf(
                    Document("\$gt", listOf("\$lastModified", incomingTimestamp)),
                    "$$field",
                    incoming,
                ),
            )

        val set =
            Document()
                .append("data", keepUnlessStale("data", bsonConverter.toDocument(resource)))
                .append("identifiers", keepUnlessStale("identifiers", identifierDocuments))
                .append("createdAt", Document("\$ifNull", listOf("\$createdAt", incomingTimestamp)))
                .append("lastModified", keepUnlessStale("lastModified", incomingTimestamp))

        return AggregationUpdate.from(listOf(AggregationOperation { Document("\$set", set) }))
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
