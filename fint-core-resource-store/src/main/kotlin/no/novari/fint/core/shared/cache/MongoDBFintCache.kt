package no.novari.fint.core.shared.cache

import com.mongodb.ErrorCategory
import com.mongodb.MongoBulkWriteException
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.DeleteOneModel
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.UpdateOptions
import no.fint.antlr.odata.ODataFilterService
import no.novari.fint.core.shared.cache.CacheDocumentCodec.Companion.FIELD_HAS_DATA
import no.novari.fint.core.shared.cache.CacheDocumentCodec.Companion.FIELD_ID
import no.novari.fint.core.shared.cache.CacheDocumentCodec.Companion.FIELD_IDENTIFIERS
import no.novari.fint.core.shared.cache.CacheDocumentCodec.Companion.FIELD_IDENTIFIER_KEY
import no.novari.fint.core.shared.cache.CacheDocumentCodec.Companion.FIELD_IDENTIFIER_VALUE
import no.novari.fint.core.shared.cache.CacheDocumentCodec.Companion.FIELD_RELATION_KEY
import no.novari.fint.core.shared.cache.CacheDocumentCodec.Companion.FIELD_RELATION_LINK
import no.novari.fint.core.shared.cache.CacheDocumentCodec.Companion.FIELD_RELATION_NAME
import no.novari.fint.core.shared.cache.CacheDocumentCodec.Companion.FIELD_RELATION_REF
import no.novari.fint.core.shared.cache.CacheDocumentCodec.Companion.FIELD_TIMESTAMP
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link
import org.bson.Document
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.IdentityHashMap
import java.util.Spliterator
import java.util.Spliterators
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream
import java.util.stream.StreamSupport

/**
 * Mongo-backed cache for [FintResource] instances.
 *
 * Each instance owns a single Mongo collection holding documents produced by [CacheDocumentCodec].
 * The collection is sorted by `(timestamp, _id)` for stable pagination and secondary-indexed by
 * identifier key/value for fast [getByIdField] lookups.
 *
 * Holds no JVM lock: timestamp monotonicity is enforced by a single conditional upsert in [put], so
 * concurrent writers — including separate replicas sharing the same Mongo — cannot regress an entry
 * to an older payload.
 */
class MongoDBFintCache(
    private val mongoTemplate: MongoTemplate,
    private val codec: CacheDocumentCodec,
    private val collectionName: String,
) : FintCache {
    private val oDataFilterService = ODataFilterService()
    private val sizeMemo = AtomicReference<Pair<Long, Int>?>(null)

    init {
        ensureIndexes()
    }

    private fun collection(): MongoCollection<Document> = mongoTemplate.getCollection(collectionName)

    private fun backLinks(): MongoCollection<Document> = mongoTemplate.getCollection(BACKLINKS_COLLECTION)

    private fun backLinkFilter(
        targetId: String,
        relation: String,
        ref: String,
    ): Document =
        Document(FIELD_BL_COLL, collectionName)
            .append(FIELD_BL_TARGET, targetId)
            .append(FIELD_RELATION_NAME, relation)
            .append(FIELD_RELATION_REF, ref)

    /** All back-link rows owned by [targetIds] in this collection, grouped by target id. */
    private fun backLinkRowsFor(targetIds: Collection<String>): Map<String, List<Document>> {
        if (targetIds.isEmpty()) return emptyMap()
        val rows = HashMap<String, MutableList<Document>>()
        backLinks()
            .find(Document(FIELD_BL_COLL, collectionName).append(FIELD_BL_TARGET, Document("\$in", targetIds.toList())))
            .iterator()
            .use { cursor ->
                while (cursor.hasNext()) {
                    val row = cursor.next()
                    rows.getOrPut(row.getString(FIELD_BL_TARGET)) { mutableListOf() }.add(row)
                }
            }
        return rows
    }

    /** Merge each resource's back-link rows (one batched query) into its `_links`. */
    private fun withBackLinks(byId: Map<String, FintResource>): List<FintResource> {
        if (byId.isNotEmpty()) {
            val rows = backLinkRowsFor(byId.keys)
            byId.forEach { (id, resource) -> codec.mergeBackLinks(resource, rows[id] ?: emptyList()) }
        }
        return byId.values.toList()
    }

    /** Delete every back-link row owned by [targetIds] in this collection (target removed/evicted). */
    private fun deleteBackLinkRowsFor(targetIds: Collection<String>) {
        if (targetIds.isEmpty()) return
        backLinks().deleteMany(
            Document(FIELD_BL_COLL, collectionName).append(FIELD_BL_TARGET, Document("\$in", targetIds.toList())),
        )
    }

    /**
     * Raise the target document's timestamp so a back-link change is visible to incremental readers.
     * No-op when the target is not yet cached — the back-link row waits until a [put] creates it.
     */
    private fun bumpTargetTimestamp(
        targetId: String,
        timestamp: Long,
    ) {
        collection().updateOne(
            Document(FIELD_ID, targetId).append(FIELD_HAS_DATA, true),
            Document("\$max", Document(FIELD_TIMESTAMP, timestamp)),
        )
    }

    /**
     * Updates the shared `cache_meta` document for this collection on every accepted mutation:
     * raises `lastUpdated` to [timestamp] (monotonic `$max`, so concurrent and out-of-order bumps
     * stay idempotent) and increments a monotonic `version` counter in the same write. The version
     * is the invalidation key for the memoised [size]: `lastUpdated` alone is unreliable for that
     * (a `$max` does not move when a new entry arrives with an older timestamp, and eviction does
     * not touch it), whereas the version advances on any mutation that could change the entry count.
     * A single shared document keeps both cross-replica correct.
     */
    private fun bumpMeta(timestamp: Long) {
        mongoTemplate.getCollection(META_COLLECTION).updateOne(
            Document(FIELD_ID, collectionName),
            Document("\$max", Document(FIELD_LAST_UPDATED, timestamp))
                .append("\$inc", Document(FIELD_VERSION, 1L)),
            UpdateOptions().upsert(true),
        )
    }

    /**
     * Advances only the `version` counter, leaving `lastUpdated` untouched — used by eviction, which
     * removes entries (so the memoised [size] must be invalidated) but carries no tombstone for
     * incremental readers and so deliberately does not move `lastUpdated`.
     */
    private fun bumpVersion() {
        mongoTemplate.getCollection(META_COLLECTION).updateOne(
            Document(FIELD_ID, collectionName),
            Document("\$inc", Document(FIELD_VERSION, 1L)),
            UpdateOptions().upsert(true),
        )
    }

    private fun ensureIndexes() {
        val coll = collection()
        coll.createIndex(
            Indexes.compoundIndex(Indexes.ascending(FIELD_TIMESTAMP), Indexes.ascending(FIELD_ID)),
            IndexOptions().name("timestamp_id_idx"),
        )
        coll.createIndex(
            Indexes.compoundIndex(
                Indexes.ascending("$FIELD_IDENTIFIERS.$FIELD_IDENTIFIER_KEY"),
                Indexes.ascending("$FIELD_IDENTIFIERS.$FIELD_IDENTIFIER_VALUE"),
            ),
            IndexOptions().name("identifiers_idx"),
        )
        val bl = backLinks()
        bl.createIndex(
            Indexes.compoundIndex(
                Indexes.ascending(FIELD_BL_COLL),
                Indexes.ascending(FIELD_BL_TARGET),
                Indexes.ascending(FIELD_RELATION_NAME),
                Indexes.ascending(FIELD_RELATION_REF),
            ),
            IndexOptions().name("backlinks_owner_idx").unique(true),
        )
        bl.createIndex(
            Indexes.compoundIndex(
                Indexes.ascending(FIELD_BL_COLL),
                Indexes.ascending(FIELD_RELATION_NAME),
                Indexes.ascending(FIELD_RELATION_REF),
            ),
            IndexOptions().name("backlinks_ref_idx"),
        )
    }

    /**
     * Insert or update a resource's own payload in the cache.
     *
     * Implemented as a single conditional upsert that `$set`s only the entity-owned fields and
     * leaves `backLinks` untouched (preserving back-links a concurrent replica applied). The filter
     * matches when the stored entry is not newer (`timestamp <= [timestamp]`) or is a data-less stub
     * (`hasData != true`); otherwise the upsert attempts an insert on the duplicate `_id`, reported
     * as a rejected (older) write. No JVM lock is needed and the guarantee holds across replicas.
     *
     * @return `true` if the write was accepted, `false` if it was rejected because an existing
     *   entry has a newer timestamp.
     */
    override fun put(
        resourceId: String,
        resource: FintResource,
        timestamp: Long,
    ): Boolean =
        try {
            collection().updateOne(
                Document(FIELD_ID, resourceId)
                    .append(
                        "\$or",
                        listOf(
                            Document(FIELD_TIMESTAMP, Document("\$lte", timestamp)),
                            Document(FIELD_HAS_DATA, Document("\$ne", true)),
                        ),
                    ),
                Document("\$set", codec.toSetDocument(resource, timestamp)),
                UpdateOptions().upsert(true),
            )
            bumpMeta(timestamp)
            true
        } catch (e: MongoWriteException) {
            if (ErrorCategory.fromErrorCode(e.code) == ErrorCategory.DUPLICATE_KEY) false else throw e
        }

    /**
     * Bulk [put]: conditionally upserts all [items] in one `bulkWrite`. A per-entry duplicate-key
     * error is the expected "rejected older write" outcome and is ignored; any other bulk error is
     * rethrown. Unordered so a rejected entry does not stop the rest.
     */
    override fun putAll(
        items: List<Pair<String, FintResource>>,
        timestamp: Long,
    ) {
        if (items.isEmpty()) return
        val models =
            items.map { (resourceId, resource) ->
                UpdateOneModel<Document>(
                    Document(FIELD_ID, resourceId)
                        .append(
                            "\$or",
                            listOf(
                                Document(FIELD_TIMESTAMP, Document("\$lte", timestamp)),
                                Document(FIELD_HAS_DATA, Document("\$ne", true)),
                            ),
                        ),
                    Document("\$set", codec.toSetDocument(resource, timestamp)),
                    UpdateOptions().upsert(true),
                )
            }
        try {
            collection().bulkWrite(models, BulkWriteOptions().ordered(false))
        } catch (e: MongoBulkWriteException) {
            if (e.writeErrors.any { ErrorCategory.fromErrorCode(it.code) != ErrorCategory.DUPLICATE_KEY }) throw e
        }
        bumpMeta(timestamp)
    }

    override fun get(resourceId: String): FintResource? {
        val doc =
            collection()
                .find(Document(FIELD_ID, resourceId).append(FIELD_HAS_DATA, true))
                .first() ?: return null
        val resource = codec.fromDocument(doc)
        codec.mergeBackLinks(resource, backLinkRowsFor(listOf(resourceId))[resourceId] ?: emptyList())
        return resource
    }

    override fun lastUpdatedByResourceId(resourceId: String): Long? = lookupTimestamp(resourceId)

    private fun lookupTimestamp(resourceId: String): Long? =
        collection()
            .find(Document(FIELD_ID, resourceId))
            .projection(Document(FIELD_TIMESTAMP, 1))
            .first()
            ?.getLong(FIELD_TIMESTAMP)

    override fun getByIdField(
        field: String,
        value: Any,
    ): FintResource? {
        val criteria =
            Document(FIELD_HAS_DATA, true)
                .append(
                    FIELD_IDENTIFIERS,
                    Document(
                        "\$elemMatch",
                        Document(FIELD_IDENTIFIER_KEY, field.lowercase())
                            .append(FIELD_IDENTIFIER_VALUE, value.toString()),
                    ),
                )
        val doc = collection().find(criteria).first() ?: return null
        val id = doc.getString(FIELD_ID)
        val resource = codec.fromDocument(doc)
        codec.mergeBackLinks(resource, backLinkRowsFor(listOf(id))[id] ?: emptyList())
        return resource
    }

    /**
     * Find the ids of cached resources holding a back-link under [relation] that points to the
     * resource identified by [ref] (an `idField/idValue` suffix produced by
     * [CacheDocumentCodec.relationRef]).
     *
     * Used by relation-state reconciliation to discover which targets currently point back to a
     * given source, so removals can be computed by diffing against the published state.
     */
    override fun findIdsByBackLink(
        relation: String,
        ref: String,
    ): Set<String> {
        val ids = mutableSetOf<String>()
        backLinks()
            .find(
                Document(FIELD_BL_COLL, collectionName)
                    .append(FIELD_RELATION_NAME, relation.lowercase())
                    .append(FIELD_RELATION_REF, ref),
            ).projection(Document(FIELD_BL_TARGET, 1))
            .iterator()
            .use { cursor ->
                while (cursor.hasNext()) {
                    ids.add(cursor.next().getString(FIELD_BL_TARGET))
                }
            }
        return ids
    }

    /**
     * Batched [findIdsByBackLink] for whole-page relation reconciliation: one indexed query over the
     * `backlinks` collection returns the `(ref, target)` rows for every ref in [refs], collapsing the
     * page's per-ref round-trips to one. No array scan — back-links are individually indexed rows.
     */
    override fun findIdsByBackLinks(
        relation: String,
        refs: Set<String>,
    ): Map<String, Set<String>> {
        if (refs.isEmpty()) return emptyMap()
        val result = HashMap<String, MutableSet<String>>()
        backLinks()
            .find(
                Document(FIELD_BL_COLL, collectionName)
                    .append(FIELD_RELATION_NAME, relation.lowercase())
                    .append(FIELD_RELATION_REF, Document("\$in", refs.toList())),
            ).projection(Document(FIELD_BL_TARGET, 1).append(FIELD_RELATION_REF, 1))
            .iterator()
            .use { cursor ->
                while (cursor.hasNext()) {
                    val row = cursor.next()
                    result.getOrPut(row.getString(FIELD_RELATION_REF)) { mutableSetOf() }.add(row.getString(FIELD_BL_TARGET))
                }
            }
        return result
    }

    /**
     * Add (or replace) a back-link as a single upserted row in the `backlinks` collection, keyed by
     * `(collection, target, relation, ref)` so it is idempotent. The target document is not required
     * — a row to a not-yet-cached target simply waits for a later [put]. Raises the target's timestamp
     * when it exists so the change is visible to incremental readers.
     */
    override fun addBackLink(
        resourceId: String,
        relation: String,
        link: Link,
        timestamp: Long,
    ) {
        val entry = codec.linkEntry(relation, link) ?: return
        backLinks().updateOne(
            backLinkFilter(resourceId, entry.getString(FIELD_RELATION_NAME), entry.getString(FIELD_RELATION_REF)),
            Document(
                "\$set",
                Document(FIELD_RELATION_KEY, entry.getString(FIELD_RELATION_KEY))
                    .append(FIELD_RELATION_LINK, entry[FIELD_RELATION_LINK]),
            ),
            UpdateOptions().upsert(true),
        )
        bumpTargetTimestamp(resourceId, timestamp)
        bumpMeta(timestamp)
    }

    /**
     * Remove a back-link by deleting its row. No-op if absent. Raises the target's timestamp when
     * something was removed, mirroring [addBackLink].
     */
    override fun removeBackLink(
        resourceId: String,
        relation: String,
        ref: String,
        timestamp: Long,
    ) {
        val result = backLinks().deleteOne(backLinkFilter(resourceId, relation.lowercase(), ref))
        if (result.deletedCount > 0) {
            bumpTargetTimestamp(resourceId, timestamp)
            bumpMeta(timestamp)
        }
    }

    /**
     * Bulk back-link reconciliation for a whole page: one `bulkWrite` of row upserts (adds) and row
     * deletes (removes) against the `backlinks` collection, then a single `updateMany` raising the
     * affected targets' timestamps. No per-target array rewrite — each back-link is its own row.
     */
    override fun applyBackLinkOps(
        ops: List<BackLinkOp>,
        timestamp: Long,
    ) {
        if (ops.isEmpty()) return
        val models =
            ops.mapNotNull { op ->
                when (op) {
                    is BackLinkOp.Add -> {
                        codec.linkEntry(op.relation, op.link)?.let { entry ->
                            UpdateOneModel<Document>(
                                backLinkFilter(op.resourceId, entry.getString(FIELD_RELATION_NAME), entry.getString(FIELD_RELATION_REF)),
                                Document(
                                    "\$set",
                                    Document(FIELD_RELATION_KEY, entry.getString(FIELD_RELATION_KEY))
                                        .append(FIELD_RELATION_LINK, entry[FIELD_RELATION_LINK]),
                                ),
                                UpdateOptions().upsert(true),
                            )
                        }
                    }

                    is BackLinkOp.Remove -> {
                        DeleteOneModel<Document>(backLinkFilter(op.resourceId, op.relation.lowercase(), op.ref))
                    }
                }
            }
        if (models.isEmpty()) return
        backLinks().bulkWrite(models, BulkWriteOptions().ordered(false))
        collection().updateMany(
            Document(FIELD_ID, Document("\$in", ops.map { it.resourceId }.distinct())).append(FIELD_HAS_DATA, true),
            Document("\$max", Document(FIELD_TIMESTAMP, timestamp)),
        )
        bumpMeta(timestamp)
    }

    /**
     * Get a paged, `(timestamp, _id)`-sorted list of cached resources, optionally filtered.
     *
     * Without a [filter], `skip`/`limit` are pushed to Mongo so only the requested page is fetched
     * and deserialised (the sort is served by `timestamp_id_idx`). When [filter] is supplied the
     * cursor is streamed and filtering is applied in-app via [ODataFilterService] before pagination,
     * so OData semantics remain unchanged.
     */
    override fun getList(
        size: Long,
        offset: Long,
        sinceTimestamp: Long,
        filter: String?,
    ): List<FintResource> {
        val criteria = Document(FIELD_HAS_DATA, true)
        if (sinceTimestamp > 0L) {
            criteria.append(FIELD_TIMESTAMP, Document("\$gte", sinceTimestamp))
        }
        val find =
            collection()
                .find(criteria)
                .sort(Sorts.ascending(FIELD_TIMESTAMP, FIELD_ID))

        if (filter.isNullOrBlank()) {
            if (size > 0) {
                if (offset > 0) find.skip(offset.toInt())
                find.limit(size.toInt())
            }
            val byId = LinkedHashMap<String, FintResource>()
            find.iterator().use { c ->
                while (c.hasNext()) {
                    val doc = c.next()
                    byId[doc.getString(FIELD_ID)] = codec.fromDocument(doc)
                }
            }
            return withBackLinks(byId)
        }

        return find.iterator().use { c ->
            val idByResource = IdentityHashMap<FintResource, String>()
            val baseStream =
                StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(c, Spliterator.ORDERED or Spliterator.NONNULL),
                    false,
                )
            var resources: Stream<FintResource> =
                applyODataFilter(
                    baseStream.map { doc -> codec.fromDocument(doc).also { idByResource[it] = doc.getString(FIELD_ID) } },
                    filter,
                )
            if (size > 0) {
                if (offset > 0) {
                    resources = resources.skip(offset)
                }
                resources = resources.limit(size)
            }
            val page = resources.toList()
            val byId = LinkedHashMap<String, FintResource>()
            page.forEach { resource -> idByResource[resource]?.let { byId[it] = resource } }
            withBackLinks(byId)
            page
        }
    }

    private fun applyODataFilter(
        resources: Stream<FintResource>,
        filter: String,
    ): Stream<FintResource> {
        if (!oDataFilterService.validate(filter)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid OData filter")
        }
        return oDataFilterService.from(resources, filter)
    }

    override val lastUpdated: Long
        get() =
            mongoTemplate
                .getCollection(META_COLLECTION)
                .find(Document(FIELD_ID, collectionName))
                .first()
                ?.getLong(FIELD_LAST_UPDATED)
                ?: 0L

    /**
     * Number of cached entries holding data (`hasData=true`). The underlying `countDocuments` is a
     * full scan, so the result is memoised against the `cache_meta` version counter and recomputed
     * only after a mutation advances it. The version is read before the count so the pairing can
     * only be over-invalidated, never served stale, across concurrent writers and replicas.
     */
    override val size: Int
        get() {
            val version = currentVersion()
            sizeMemo.get()?.let { (cachedVersion, cachedSize) ->
                if (cachedVersion == version) return cachedSize
            }
            val computed = collection().countDocuments(Document(FIELD_HAS_DATA, true)).toInt()
            sizeMemo.set(version to computed)
            return computed
        }

    private fun currentVersion(): Long =
        mongoTemplate
            .getCollection(META_COLLECTION)
            .find(Document(FIELD_ID, collectionName))
            .projection(Document(FIELD_VERSION, 1))
            .first()
            ?.getLong(FIELD_VERSION)
            ?: 0L

    override fun remove(
        resourceId: String,
        timestamp: Long,
    ) {
        val result =
            collection().deleteOne(
                Document(FIELD_ID, resourceId)
                    .append(FIELD_TIMESTAMP, Document("\$lt", timestamp)),
            )
        if (result.deletedCount > 0) {
            bumpMeta(timestamp)
        }
    }

    override fun removeAll(
        resourceIds: List<String>,
        timestamp: Long,
    ): List<Pair<String, FintResource>> {
        if (resourceIds.isEmpty()) return emptyList()
        val existing = mutableListOf<Pair<String, FintResource>>()
        collection()
            .find(Document(FIELD_ID, Document("\$in", resourceIds)).append(FIELD_HAS_DATA, true))
            .iterator()
            .use { cursor ->
                while (cursor.hasNext()) {
                    val doc = cursor.next()
                    existing.add(codec.resourceId(doc) to codec.fromDocument(doc))
                }
            }
        val models =
            resourceIds.map { resourceId ->
                DeleteOneModel<Document>(
                    Document(FIELD_ID, resourceId).append(FIELD_TIMESTAMP, Document("\$lt", timestamp)),
                )
            }
        val result = collection().bulkWrite(models, BulkWriteOptions().ordered(false))
        if (result.deletedCount > 0) {
            deleteBackLinkRowsFor(resourceIds)
            bumpMeta(timestamp)
        }
        return existing
    }

    /**
     * Evict cache entries with `timestamp < [timestamp]`. Returns the evicted `(id, resource)`
     * pairs so callers can publish relation deletes for them.
     *
     * Deliberately holds no JVM lock: the write lock exists only to serialise `put`'s
     * read-modify-write, while eviction is a `deleteMany` (atomic in Mongo) plus a read. Holding
     * the write lock here would block every concurrent `put` to this collection for the full sweep
     * — the entire expired set is materialised and deserialised — stalling ingestion. The
     * `timestamp < threshold` predicate is safe under concurrent puts: a re-cached entry carries a
     * newer timestamp and is not matched.
     *
     * The entire expired set is materialised in heap; for the typical full-sync sweep this is
     * bounded by the number of stale entries for a single resource type.
     */
    override fun evictExpired(timestamp: Long): Set<Pair<String, FintResource>> {
        val criteria =
            Document(FIELD_TIMESTAMP, Document("\$lt", timestamp))
                .append(FIELD_HAS_DATA, true)
        val coll = collection()
        val expired = mutableSetOf<Pair<String, FintResource>>()
        coll.find(criteria).iterator().use { cursor ->
            while (cursor.hasNext()) {
                val doc = cursor.next()
                expired.add(codec.resourceId(doc) to codec.fromDocument(doc))
            }
        }
        if (expired.isNotEmpty()) {
            coll.deleteMany(criteria)
            deleteBackLinkRowsFor(expired.map { it.first })
            bumpVersion()
        }
        return expired
    }

    companion object {
        const val META_COLLECTION = "cache_meta"
        const val BACKLINKS_COLLECTION = "backlinks"
        const val FIELD_LAST_UPDATED = "lastUpdated"
        const val FIELD_VERSION = "version"
        private const val FIELD_BL_COLL = "coll"
        private const val FIELD_BL_TARGET = "target"
    }
}
