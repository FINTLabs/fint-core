package no.novari.core.shared.store

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.fint.core.model.FintModel
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
import org.springframework.data.mongodb.core.query.Update
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
    private val sizeCache: Cache<String, Long> =
        Caffeine.newBuilder().expireAfterWrite(properties.countCacheTtl).build()

    fun prepareCollection(collectionName: String) = ensureIndexes(collectionName)

    /**
     * Applies a batch of writes and deletes, grouped by collection, and reports what happened to
     * each. If the batch holds several operations for the same id, only the one with the newest
     * `lastDelivered` timestamp is applied, and only that one is reported.
     * A write is stale, and skipped, when the store already holds a newer delivery for that id,
     * so a late resource can never update or delete a newer one. If both have the exact same timestamp,
     * the new one wins.
     *
     * A save whose content matches what is stored only moves `lastDelivered`. A save with other
     * content, or for an id that is not stored, stores the content and its hash and moves
     * `lastModified` and `lastDelivered` together. `createdAt` is set once and kept.
     *
     * The store reads the stored timestamps and hashes first and only sends what has to change.
     * The outcomes are only trustworthy inside a Mongo transaction.
     */
    fun applyAll(writes: List<ResourceWrite>): List<WriteOutcome> =
        writes
            .groupBy { it.collectionName }
            .flatMap { (collectionName, collectionWrites) -> applyToCollection(collectionName, collectionWrites) }

    // TODO: Can be removed, old api. Kept only so tests wont be refactored in unrelated branches.
    fun saveAll(writes: List<Save>): List<WriteOutcome> = applyAll(writes)

    private fun applyToCollection(
        collectionName: String,
        writes: List<ResourceWrite>,
    ): List<WriteOutcome> {
        ensureIndexes(collectionName)

        val latestById = writes.sortedBy { it.timestamp }.associateBy { it.resourceId }
        val stored = findFingerprints(latestById.keys, collectionName)
        val planned = latestById.values.map { it.plan(stored[it.resourceId]) }

        val bulkOps = template.bulkOps(BulkOperations.BulkMode.UNORDERED, collectionName)
        val sent = planned.count { bulkOps.add(it) }
        if (sent > 0) bulkOps.execute()

        return planned.map { WriteOutcome(it.write, it.result) }
    }

    private fun findFingerprints(
        ids: Collection<String>,
        collectionName: String,
    ): Map<String, ResourceFingerprint> {
        val query = Query.query(Criteria.where("_id").`in`(ids))
        query.fields().include("lastModified", "lastDelivered", "contentHash")

        return template
            .find(query, ResourceFingerprint::class.java, collectionName)
            .associateBy { it.id }
    }

    private fun ResourceWrite.plan(stored: ResourceFingerprint?): PlannedWrite {
        if (stored != null && stored.delivered.isAfter(timestamp)) return PlannedWrite(this, WriteResult.STALE)

        return when (this) {
            is Delete -> PlannedWrite(this, WriteResult.DELETED)
            is Save -> planSave(stored)
        }
    }

    /**
     * Decides what a save does by comparing its hash with the stored one. Nothing stored means
     * the resource is new. The same hash means nothing changed. Any other case counts as a
     * change and is written, including a document stored before hashes existed, since it has
     * no hash to compare with.
     */
    private fun Save.planSave(stored: ResourceFingerprint?): PlannedWrite {
        val form = bsonConverter.toStorageForm(resource)
        val result =
            when {
                stored == null -> WriteResult.NEW
                stored.contentHash == form.contentHash -> WriteResult.UNCHANGED
                else -> WriteResult.CHANGED
            }

        return PlannedWrite(this, result, form)
    }

    private class PlannedWrite(
        val write: ResourceWrite,
        val result: WriteResult,
        val form: StorageForm? = null,
    )

    /**
     * Adds the operation the plan calls for and says whether one was added. A stale write adds
     * nothing. An unchanged save only moves `lastDelivered` forward, through `$max`, so a late
     * duplicate can never move it back.
     */
    private fun BulkOperations.add(planned: PlannedWrite): Boolean {
        val write = planned.write
        val byId = Query.query(Criteria.where("_id").`is`(write.resourceId))

        when (planned.result) {
            WriteResult.STALE -> {
                return false
            }

            WriteResult.DELETED -> {
                remove(byId.addCriteria(deliveredAtOrBefore(write.timestamp)))
            }

            WriteResult.UNCHANGED -> {
                updateOne(byId, Update().max("lastDelivered", Date.from(write.timestamp)))
            }

            WriteResult.NEW, WriteResult.CHANGED -> {
                upsert(byId, (write as Save).toGuardedUpdate(checkNotNull(planned.form)))
            }
        }
        return true
    }

    /**
     * Stores the content unless the document already holds a newer delivery. The check runs
     * inside Mongo, so a write that lands between the read and this update still cannot be
     * overtaken by an older one. Each field is set through `$cond`, which is Mongo's if-else:
     * when the stored delivery time is newer than the incoming timestamp the stored value is kept,
     * otherwise the incoming one is written. A document without `lastDelivered` compares its
     * `lastModified` instead.
     */
    private fun Save.toGuardedUpdate(form: StorageForm): AggregationUpdate {
        val incomingTimestamp = Date.from(timestamp)
        val identifierDocuments =
            resource.toIdentifierRefs().map { Document("field", it.field).append("value", it.value) }
        val storedDelivery = Document("\$ifNull", listOf("\$lastDelivered", "\$lastModified"))

        fun keepUnlessStale(
            field: String,
            incoming: Any,
        ): Document =
            Document(
                "\$cond",
                listOf(
                    Document("\$gt", listOf(storedDelivery, incomingTimestamp)),
                    "$$field",
                    incoming,
                ),
            )

        val set =
            Document()
                .append("data", keepUnlessStale("data", form.document))
                .append("identifiers", keepUnlessStale("identifiers", identifierDocuments))
                .append("contentHash", keepUnlessStale("contentHash", form.contentHash))
                .append("createdAt", Document("\$ifNull", listOf("\$createdAt", incomingTimestamp)))
                .append("lastModified", keepUnlessStale("lastModified", incomingTimestamp))
                .append("lastDelivered", keepUnlessStale("lastDelivered", incomingTimestamp))

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
        if (anchor == null) {
            return find(
                orderedQuery(filter?.since, Sort.Direction.ASC),
                size,
                collectionName,
                hintFor(filter),
            )
        }

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

    /**
     * The resource types [orgId] has a collection for, sorted by type. A collection stays after an
     * eviction empties it, so a type that was synced once is still listed.
     */
    fun storedCoordinates(orgId: OrgId): List<ResourceCoordinate> {
        val existing = template.collectionNames
        return FintModel.refs
            .map { ResourceCoordinate.of(orgId, it) }
            .filter { it.toCollectionName() in existing }
            .sortedBy { it.toResourceUri() }
    }

    /**
     * When an adapter last delivered anything to the resource type, changed or not, which is what
     * `last-updated` reports. Before any document has `lastDelivered` it falls back to the newest
     * `lastModified`, which was the delivery time when those documents were written.
     */
    fun getLastUpdated(coordinate: ResourceCoordinate): Instant? {
        val collectionName = coordinate.toCollectionName()

        return newestBy("lastDelivered", collectionName)?.lastDelivered
            ?: newestBy("lastModified", collectionName)?.lastModified
    }

    fun findStoredIds(
        ids: Collection<String>,
        collectionName: String,
    ): Set<String> {
        if (ids.isEmpty()) return emptySet()

        val query = Query.query(Criteria.where("_id").`in`(ids))
        query.fields().include("_id")

        return template.find(query, Document::class.java, collectionName).mapTo(mutableSetOf()) { it.getString("_id") }
    }

    private fun newestBy(
        field: String,
        collectionName: String,
    ): ResourceEntry? =
        template.findOne<ResourceEntry>(
            Query().with(Sort.by(Sort.Direction.DESC, field)).limit(1),
            collectionName,
        )

    /**
     * Reads up to [limit] ids of entries last delivered before [threshold], through the
     * `last_delivered` index. An entry written before `lastDelivered` existed is read by its
     * `lastModified` instead, which was its delivery time back then.
     */
    fun findIdsOlderThan(
        threshold: Instant,
        limit: Int,
        collectionName: String,
    ): List<String> {
        val query =
            Query
                .query(deliveredBefore(threshold))
                .limit(limit)
                .withHint(LAST_DELIVERED_INDEX)
        query.fields().include("_id")

        return template.find(query, ResourceId::class.java, collectionName).map { it.id }
    }

    fun deleteStaleByIds(
        ids: Collection<String>,
        threshold: Instant,
        collectionName: String,
    ): Long {
        if (ids.isEmpty()) return 0

        val query = Query.query(Criteria.where("_id").`in`(ids)).addCriteria(deliveredBefore(threshold))

        return template.remove(query, collectionName).deletedCount
    }

    private fun deliveredBefore(threshold: Instant): Criteria = delivered { lt(Date.from(threshold)) }

    private fun deliveredAtOrBefore(timestamp: Instant): Criteria = delivered { lte(Date.from(timestamp)) }

    /**
     * Matches entries by their delivery time: `lastDelivered` when the entry has one, otherwise
     * `lastModified`. Both branches start on `lastDelivered`, so the `last_delivered` index can
     * serve them.
     */
    private fun delivered(compare: Criteria.() -> Criteria): Criteria =
        Criteria().orOperator(
            Criteria.where("lastDelivered").compare(),
            Criteria
                .where("lastDelivered")
                .`is`(null)
                .and("lastModified")
                .compare(),
        )

    /**
     * Drops the whole collection. A write that lands at the same moment creates the collection
     * again, so the indexes are checked once more afterwards.
     */
    fun dropCollection(collectionName: String) {
        template.dropCollection(collectionName)
        indexedCollections.remove(collectionName)
        sizeCache.invalidate(collectionName)

        if (template.collectionExists(collectionName)) ensureIndexes(collectionName)
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
            Index().on("lastDelivered", Sort.Direction.ASC).named(LAST_DELIVERED_INDEX),
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
        const val LAST_DELIVERED_INDEX = "last_delivered"
    }
}
