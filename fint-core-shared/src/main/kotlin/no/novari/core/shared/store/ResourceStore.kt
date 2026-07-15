package no.novari.core.shared.store

import no.novari.core.shared.nonNullIdentifikators
import no.novari.fint.model.resource.FintResource
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.find
import org.springframework.data.mongodb.core.findAll
import org.springframework.data.mongodb.core.findById
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ResourceStore(
    private val template: MongoTemplate,
    private val bsonConverter: FintResourceBsonConverter,
) {
    fun save(
        resourceId: String,
        collectionName: String,
        resource: FintResource,
        timestamp: Instant,
    ) {
        val identifiers =
            resource.nonNullIdentifikators().map { (field, identifier) ->
                IdentifierRef(field, identifier.identifikatorverdi)
            }

        // Data field should be stored as org.bson.Document.
        val data = bsonConverter.toDocument(resource)
        val resourceEntry =
            ResourceEntry(
                resourceId,
                data,
                identifiers,
                timestamp,
                timestamp,
            )

        template.save(resourceEntry, collectionName)
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
