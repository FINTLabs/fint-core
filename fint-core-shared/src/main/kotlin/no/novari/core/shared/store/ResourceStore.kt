package no.novari.core.shared.store

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
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
    private val properties: ResourceStoreProperties = ResourceStoreProperties(),
) {
    private val indexedCollections = ConcurrentHashMap.newKeySet<String>()
    private val sizeCache: Cache<String, Long> = Caffeine.newBuilder().expireAfterWrite(properties.countCacheTtl).build()

    fun prepareCollection(collectionName: String) = ensureIndexes(collectionName)

    /**
     * Applies a batch of writes and deletes, grouped by collection, and returns the writes that
     * took effect. If the batch holds several operations for the same id, only the one with the
     * newest timestamp is applied. A write takes effect unless the store already holds a newer
     * `lastModified` for that id, so a late resource can never update or delete a newer one. If
     * both have the exact same timestamp, the new one wins. The original `createdAt` value is
     * always kept.
     *
     * The store reads the stored timestamps first and only sends the writes that will take
     * effect. The returned list is only trustworthy inside a Mongo transaction.
     */
    fun applyAll(writes: List<ResourceWrite>): List<ResourceWrite> =
        writes
            .groupBy { it.collectionName }
            .flatMap { (collectionName, collectionWrites) -> applyToCollection(collectionName, collectionWrites) }

    private fun applyToCollection(
        collectionName: String,
        writes: List<ResourceWrite>,
    ): List<ResourceWrite> {
        ensureIndexes(collectionName)

        val latestById = writes.sortedBy { it.timestamp }.associateBy { it.resourceId }
        val storedLastModified = findLastModified(latestById.keys, collectionName)
        val effective = latestById.values.filter { it.takesEffect(storedLastModified[it.resourceId]) }
        if (effective.isEmpty()) return effective

        val bulkOps = template.bulkOps(BulkOperations.BulkMode.UNORDERED, collectionName)
        effective.forEach { bulkOps.add(it) }
        bulkOps.execute()

        return effective
    }

    private fun findLastModified(
        ids: Collection<String>,
        collectionName: String,
    ): Map<String, Instant> {
        val query = Query.query(Criteria.where("_id").`in`(ids))
        query.fields().include("lastModified")

        return template
            .find(query, ResourceTimestamp::class.java, collectionName)
            .associate { it.id to it.lastModified }
    }

    private fun ResourceWrite.takesEffect(storedLastModified: Instant?): Boolean =
        storedLastModified == null || !storedLastModified.isAfter(timestamp)

    fun saveAll(writes: List<Save>): List<ResourceWrite> = applyAll(writes)

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
        since: Instant?,
        collectionName: String,
    ): List<ResourceEntry> = template.find<ResourceEntry>(orderedQuery(since, Sort.Direction.ASC), collectionName)

    /**
     * Reads one page by skipping [offset] entries. Every skipped entry costs a document read, so a
     * deep offset is slow. Pages read through [findPageAfter] and [findPageBefore] are not.
     */
    fun findPage(
        filter: SinceFilter?,
        size: Int,
        offset: Long,
        collectionName: String,
    ): List<ResourceEntry> = find(orderedQuery(filter?.since, Sort.Direction.ASC).skip(offset), size, collectionName, hintFor(filter))

    /**
     * Reads the [size] entries that follow [anchor], the last entry of the page the caller already
     * has. Entries that share the anchor's timestamp and have a larger id come first, then the
     * entries with a later timestamp. Without an anchor this is the first page.
     */
    fun findPageAfter(
        anchor: PageAnchor?,
        filter: SinceFilter?,
        size: Int,
        collectionName: String,
    ): List<ResourceEntry> {
        if (anchor == null) return find(orderedQuery(filter?.since, Sort.Direction.ASC), size, collectionName, hintFor(filter))

        val createdAt = Date.from(anchor.createdAt)
        val sameTimestamp =
            find(
                orderedQuery(filter?.since, Sort.Direction.ASC)
                    .addCriteria(
                        Criteria
                            .where("createdAt")
                            .`is`(createdAt)
                            .and("_id")
                            .gt(anchor.id),
                    ),
                size,
                collectionName,
                hintFor(filter),
            )
        if (sameTimestamp.size >= size) return sameTimestamp

        val later =
            find(
                orderedQuery(filter?.since, Sort.Direction.ASC).addCriteria(Criteria.where("createdAt").gt(createdAt)),
                size - sameTimestamp.size,
                collectionName,
                hintFor(filter),
            )
        return sameTimestamp + later
    }

    /**
     * Reads the [size] entries before [anchor], the first entry of the page the caller already
     * has. The read runs backwards, entries that share the anchor's timestamp and have a smaller
     * id first, then earlier entries, and the result is turned around so it is ascending like
     * every other page.
     */
    fun findPageBefore(
        anchor: PageAnchor,
        filter: SinceFilter?,
        size: Int,
        collectionName: String,
    ): List<ResourceEntry> {
        val createdAt = Date.from(anchor.createdAt)
        val sameTimestamp =
            find(
                orderedQuery(filter?.since, Sort.Direction.DESC)
                    .addCriteria(
                        Criteria
                            .where("createdAt")
                            .`is`(createdAt)
                            .and("_id")
                            .lt(anchor.id),
                    ),
                size,
                collectionName,
                hintFor(filter),
            )
        if (sameTimestamp.size >= size) return sameTimestamp.reversed()

        val earlier =
            find(
                orderedQuery(filter?.since, Sort.Direction.DESC).addCriteria(Criteria.where("createdAt").lt(createdAt)),
                size - sameTimestamp.size,
                collectionName,
                hintFor(filter),
            )
        return (sameTimestamp + earlier).reversed()
    }

    /**
     * Counts the entries modified at or after [since], or every entry when [since] is null. Paged
     * reads use the number as `total_items` and `/cache/size` reports it too. The unfiltered count
     * scans the whole collection, so it is cached per collection for
     * [ResourceStoreProperties.countCacheTtl]. A filtered count always reads the database.
     */
    fun count(
        since: Instant?,
        collectionName: String,
    ): Long {
        if (since != null) {
            val query = Query().apply { criteria(since)?.let { addCriteria(it) } }
            return template.exactCount(query, ResourceEntry::class.java, collectionName)
        }

        return sizeCache.get(collectionName) { template.exactCount(Query(), ResourceEntry::class.java, collectionName) }
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
     * shape, which lets Mongo walk the index instead of sorting the whole collection. A [since]
     * keeps only the entries modified at or after that instant.
     */
    private fun orderedQuery(
        since: Instant?,
        direction: Sort.Direction,
    ): Query =
        Query().apply {
            criteria(since)?.let { addCriteria(it) }
            with(Sort.by(direction, "createdAt", "_id"))
        }

    private fun criteria(since: Instant?): Criteria? = since?.let { Criteria.where("lastModified").gte(Date.from(it)) }

    /**
     * Picks the index a page is read through. Without a filter, or with one that matches many
     * entries, the read walks `created_at_id` in page order and drops the entries the filter
     * rejects. With a filter that matches few entries that walk visits most of the collection
     * before it finds them, so the read goes through `last_modified` instead and sorts the few
     * matches. The cut-over is [ResourceStoreProperties.deltaHintThreshold].
     */
    private fun hintFor(filter: SinceFilter?): String =
        if (filter != null && filter.matches <= properties.deltaHintThreshold) LAST_MODIFIED_INDEX else CREATED_AT_ID_INDEX

    private fun find(
        query: Query,
        limit: Int,
        collectionName: String,
        hint: String,
    ): List<ResourceEntry> = template.find<ResourceEntry>(query.limit(limit).withHint(hint), collectionName)

    private fun ensureIndexes(collectionName: String) {
        if (!indexedCollections.add(collectionName)) return

        template.indexOps(collectionName).createIndex(
            Index().on("lastModified", Sort.Direction.ASC).named(LAST_MODIFIED_INDEX),
        )

        template.indexOps(collectionName).createIndex(
            Index()
                .on("createdAt", Sort.Direction.ASC)
                .on("_id", Sort.Direction.ASC)
                .named(CREATED_AT_ID_INDEX),
        )
    }

    companion object {
        const val CREATED_AT_ID_INDEX = "created_at_id"
        const val LAST_MODIFIED_INDEX = "last_modified"
    }
}
