package no.novari.core.shared.store

import no.novari.core.shared.model.ResourceCoordinate
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
import java.util.concurrent.ConcurrentHashMap

@Service
class ResourceStore(
    private val template: MongoTemplate,
    private val bsonConverter: FintResourceBsonConverter,
) {
    private val indexedCollections = ConcurrentHashMap.newKeySet<String>()

    fun prepareCollection(collectionName: String) = ensureIndexes(collectionName)

    /**
     * Applies a batch of writes and deletes, grouped by collection. If the batch holds several
     * operations for the same id, only the one with the newest timestamp is applied. Each
     * operation also checks the stored `lastModified`, so a slow writer working through a
     * backlog can never overwrite or delete a newer write that already came in another way. If
     * both have the exact same timestamp, the new one wins. The original `createdAt` value is
     * always kept.
     */
    fun applyAll(operations: List<ResourceWrite>) =
        operations
            .groupBy { it.collectionName }
            .forEach { (collectionName, collectionOperations) ->
                ensureIndexes(collectionName)

                val latestById =
                    collectionOperations
                        .sortedBy { it.timestamp }
                        .associateBy { it.resourceId }

                val bulkOps = template.bulkOps(BulkOperations.BulkMode.UNORDERED, collectionName)
                latestById.values.forEach { bulkOps.add(it) }
                bulkOps.execute()
            }

    fun saveAll(writes: List<Save>) = applyAll(writes)

    private fun BulkOperations.add(operation: ResourceWrite) {
        val byId = Query.query(Criteria.where("_id").`is`(operation.resourceId))

        when (operation) {
            is Save -> upsert(byId, operation.toGuardedUpdate())
            is Delete -> remove(byId.addCriteria(notNewerThan(operation.timestamp)))
        }
    }

    private fun notNewerThan(timestamp: Instant) = Criteria.where("lastModified").lte(Date.from(timestamp))

    /**
     * Only updates the document if its newer than the existing document.
     */
    private fun Save.toGuardedUpdate(): AggregationUpdate {
        val incomingTimestamp = Date.from(timestamp)
        val identifierDocuments =
            resource.toIdentifierRefs().map { Document("field", it.field).append("value", it.value) }

        // `$cond` is Mongo's version of a ternary operator: `condition ? ifTrue : ifFalse`.
        // Calling keepUnlessStale("data", newData) builds:
        //   { $cond: [ { $gt: ["$lastModified", incomingTimestamp] }, "$data", newData ] }
        // which Mongo reads as: storedLastModified > incomingTimestamp ? storedData : newData
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

    fun findByResourceId(
        resourceId: String,
        collectionName: String,
    ) = template.findById<ResourceEntry>(
        resourceId,
        collectionName,
    )

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
    ): List<ResourceEntry> = template.find<ResourceEntry>(pageQuery(filter, size, offset), collectionName)

    /**
     * Counts the entries that match [filter]. Paged reads use the number as `total_items` and
     * `/cache/size` reports it too, so use the same filter as [findPage].
     *
     * Without a filter the number comes from the collection's statistics, which is fast but can
     * lag slightly behind the latest writes. With a filter the matching documents are counted.
     */
    fun count(
        filter: Criteria?,
        collectionName: String,
    ): Long =
        if (filter == null) {
            template.estimatedCount(collectionName)
        } else {
            template.exactCount(Query.query(filter), ResourceEntry::class.java, collectionName)
        }

    fun getCacheSize(coordinate: ResourceCoordinate): Long = count(null, coordinate.toCollectionName())

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

    fun findIdentitiesOlderThan(
        threshold: Instant,
        collectionName: String,
    ): List<ResourceIdentity> {
        val query = Query.query(Criteria.where("lastModified").lt(Date.from(threshold)))
        query.fields().include("identifiers")

        return template.find(query, ResourceIdentity::class.java, collectionName)
    }

    fun deleteStaleByIds(
        ids: Collection<String>,
        threshold: Instant,
        collectionName: String,
    ): Long {
        if (ids.isEmpty()) return 0

        val query =
            Query.query(
                Criteria
                    .where("_id")
                    .`in`(ids)
                    .and("lastModified")
                    .lt(Date.from(threshold)),
            )

        return template.remove(query, collectionName).deletedCount
    }

    /**
     * The query behind every list read. Results are ordered by `createdAt` and then `_id`, so a
     * resource keeps its place in the list when it is updated, and two resources created in the
     * same millisecond always come back in the same order. The `created_at_id` index has the same
     * shape, which lets Mongo walk the index instead of sorting the whole collection.
     */
    internal fun baseQuery(filter: Criteria?): Query =
        Query().apply {
            filter?.let { addCriteria(it) }
            with(Sort.by(Sort.Direction.ASC, "createdAt", "_id"))
        }

    internal fun pageQuery(
        filter: Criteria?,
        size: Int,
        offset: Long,
    ): Query = Query.of(baseQuery(filter)).skip(offset).limit(size)

    private fun ensureIndexes(collectionName: String) {
        if (!indexedCollections.add(collectionName)) return

        template.indexOps(collectionName).createIndex(
            Index().on("lastModified", Sort.Direction.ASC).named("last_modified"),
        )

        template.indexOps(collectionName).createIndex(
            Index()
                .on("createdAt", Sort.Direction.ASC)
                .on("_id", Sort.Direction.ASC)
                .named("created_at_id"),
        )
    }
}
