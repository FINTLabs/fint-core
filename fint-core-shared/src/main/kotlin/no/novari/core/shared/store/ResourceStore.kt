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
import java.util.concurrent.ConcurrentHashMap

@Service
class ResourceStore(
    private val template: MongoTemplate,
    private val bsonConverter: FintResourceBsonConverter,
) {
    private val indexedCollections = ConcurrentHashMap.newKeySet<String>()

    fun prepareCollection(collectionName: String) = ensureIndexes(collectionName)

    /**
     * Inserts or updates a batch of resources, grouped by collection. Each write only takes
     * effect if it isn't older than what's already stored, so a slow writer working through a
     * backlog can never overwrite a newer write that already came in another way. If both writes
     * have the exact same timestamp, the new one wins. The original `createdAt` value is always
     * kept.
     */
    fun saveAll(writes: List<ResourceWrite>) {
        if (writes.isEmpty()) return

        writes
            .groupBy { it.collectionName }
            .forEach { (collectionName, collectionWrites) ->
                ensureIndexes(collectionName)

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
     * Builds the update that enforces the "don't overwrite a newer write" rule from [saveAll].
     * This check has to live inside the update itself and not the query, because of how upsert
     * works: if the query matches, Mongo updates the document; if it doesn't, Mongo inserts a new
     * one. Putting the timestamp check in the query would make an old write look like "no match",
     * so Mongo would try to insert a second document with the same id and fail. Instead, the
     * query only matches on id, and each field in the update uses `$cond` to decide for itself
     * whether to keep the stored value or take the new one.
     *
     * Every field uses the exact same condition (via `keepUnlessStale`), so the write always
     * applies fully or not at all. There's no way to end up with a document that's part old and
     * part new.
     */
    private fun ResourceWrite.toGuardedUpdate(): AggregationUpdate {
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
     * Reads up to [limit] resources last written before [threshold], as ids and identifiers only.
     *
     * This is how an eviction finds what a completed full sync left behind: everything the sync
     * carried was written at or after its earliest write, so anything still older than that is
     * something the adapter no longer has. A resource written by the event path during the sync
     * is newer than [threshold] too, so it is never picked up here.
     */
    fun findIdentitiesOlderThan(
        threshold: Instant,
        limit: Int,
        collectionName: String,
    ): List<ResourceIdentity> {
        val query =
            Query
                .query(Criteria.where("lastModified").lt(Date.from(threshold)))
                .limit(limit)
        query.fields().include("identifiers")

        return template.find(query, ResourceIdentity::class.java, collectionName)
    }

    /**
     * Deletes those of [ids] still last written before [threshold], and returns how many went.
     *
     * The threshold is repeated here even though the ids came from a read that already applied
     * it, because a client write can land in between and make one of them current again. Deleting
     * on the id alone would throw that write away; this way the write simply keeps its resource.
     */
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

    private fun baseQuery(filter: Criteria?): Query =
        Query().apply {
            filter?.let { addCriteria(it) }
            with(Sort.by(Sort.Direction.ASC, "createdAt", "_id"))
        }

    /**
     * The `lastModified` index serves both the eviction sweep's range scan and the newest-first
     * lookup behind [getLastUpdated]. Creating an index is not allowed inside a Mongo
     * transaction, so [prepareCollection] exists for callers that write inside one.
     */
    private fun ensureIndexes(collectionName: String) {
        if (!indexedCollections.add(collectionName)) return

        template.indexOps(collectionName).createIndex(
            Index().on("lastModified", Sort.Direction.ASC).named("last_modified"),
        )
    }
}
