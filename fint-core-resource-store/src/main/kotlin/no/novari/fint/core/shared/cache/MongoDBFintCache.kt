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
import no.novari.fint.core.shared.cache.CacheDocumentCodec.Companion.FIELD_BACK_LINKS
import no.novari.fint.core.shared.cache.CacheDocumentCodec.Companion.FIELD_HAS_DATA
import no.novari.fint.core.shared.cache.CacheDocumentCodec.Companion.FIELD_ID
import no.novari.fint.core.shared.cache.CacheDocumentCodec.Companion.FIELD_IDENTIFIERS
import no.novari.fint.core.shared.cache.CacheDocumentCodec.Companion.FIELD_IDENTIFIER_KEY
import no.novari.fint.core.shared.cache.CacheDocumentCodec.Companion.FIELD_IDENTIFIER_VALUE
import no.novari.fint.core.shared.cache.CacheDocumentCodec.Companion.FIELD_RELATION_NAME
import no.novari.fint.core.shared.cache.CacheDocumentCodec.Companion.FIELD_RELATION_REF
import no.novari.fint.core.shared.cache.CacheDocumentCodec.Companion.FIELD_TIMESTAMP
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link
import org.bson.Document
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
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
        coll.createIndex(
            Indexes.compoundIndex(
                Indexes.ascending("$FIELD_BACK_LINKS.$FIELD_RELATION_NAME"),
                Indexes.ascending("$FIELD_BACK_LINKS.$FIELD_RELATION_REF"),
            ),
            IndexOptions().name("back_links_idx"),
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
                Document("\$set", codec.toSetDocument(resource, timestamp))
                    .append("\$setOnInsert", Document(FIELD_BACK_LINKS, emptyList<Document>())),
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
                    Document("\$set", codec.toSetDocument(resource, timestamp))
                        .append("\$setOnInsert", Document(FIELD_BACK_LINKS, emptyList<Document>())),
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
        return codec.fromDocument(doc)
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
        return codec.fromDocument(doc)
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
        val criteria =
            Document(
                FIELD_BACK_LINKS,
                Document(
                    "\$elemMatch",
                    Document(FIELD_RELATION_NAME, relation.lowercase())
                        .append(FIELD_RELATION_REF, ref),
                ),
            )
        val ids = mutableSetOf<String>()
        collection()
            .find(criteria)
            .projection(Document(FIELD_ID, 1))
            .iterator()
            .use { cursor ->
                while (cursor.hasNext()) {
                    ids.add(cursor.next().getString(FIELD_ID))
                }
            }
        return ids
    }

    /**
     * Batched [findIdsByBackLink] for whole-page relation reconciliation: one aggregation matches
     * every target holding a back-link under [relation] to any of [refs] and projects which of those
     * refs each holds, so P×R per-ref round-trips collapse to one query per (collection, relation).
     * The back-link arrays stay server-side; only the matched refs and ids cross the wire.
     */
    override fun findIdsByBackLinks(
        relation: String,
        refs: Set<String>,
    ): Map<String, Set<String>> {
        if (refs.isEmpty()) return emptyMap()
        val relationName = relation.lowercase()
        val refList = refs.toList()
        val matchedRefs =
            Document(
                "\$map",
                Document(
                    "input",
                    Document(
                        "\$filter",
                        Document("input", Document("\$ifNull", listOf("\$$FIELD_BACK_LINKS", emptyList<Document>())))
                            .append("as", "b")
                            .append(
                                "cond",
                                Document(
                                    "\$and",
                                    listOf(
                                        Document("\$eq", listOf("\$\$b.$FIELD_RELATION_NAME", relationName)),
                                        Document("\$in", listOf("\$\$b.$FIELD_RELATION_REF", refList)),
                                    ),
                                ),
                            ),
                    ),
                ).append("as", "b").append("in", "\$\$b.$FIELD_RELATION_REF"),
            )
        val pipeline =
            listOf(
                Document(
                    "\$match",
                    Document(
                        FIELD_BACK_LINKS,
                        Document(
                            "\$elemMatch",
                            Document(FIELD_RELATION_NAME, relationName)
                                .append(FIELD_RELATION_REF, Document("\$in", refList)),
                        ),
                    ),
                ),
                Document("\$project", Document(FIELD_MATCHED_REFS, matchedRefs)),
            )
        val result = HashMap<String, MutableSet<String>>()
        collection().aggregate(pipeline).iterator().use { cursor ->
            while (cursor.hasNext()) {
                val doc = cursor.next()
                val id = doc.getString(FIELD_ID)

                @Suppress("UNCHECKED_CAST")
                val matched = doc.get(FIELD_MATCHED_REFS) as? List<String> ?: emptyList()
                matched.forEach { ref -> result.getOrPut(ref) { mutableSetOf() }.add(id) }
            }
        }
        return result
    }

    /**
     * Atomic back-link add via an aggregation-pipeline update: existing entries for the same
     * `(relation, ref)` are filtered out and the new entry appended, so the write is idempotent and
     * needs no read-modify-write. Upserts a data-less stub (`hasData=false`, no `timestamp`) when the
     * target is absent; on a stub the timestamp is left unset so a later [put] still recognises it.
     * On a resource that already holds data the timestamp is raised to [timestamp] so the change is
     * visible to incremental readers.
     */
    override fun addBackLink(
        resourceId: String,
        relation: String,
        link: Link,
        timestamp: Long,
    ) {
        val entry = codec.linkEntry(relation, link) ?: return
        val ref = entry.getString(FIELD_RELATION_REF)
        val relationName = entry.getString(FIELD_RELATION_NAME)
        val filtered =
            Document(
                "\$filter",
                Document("input", Document("\$ifNull", listOf("\$$FIELD_BACK_LINKS", emptyList<Document>())))
                    .append("as", "b")
                    .append(
                        "cond",
                        Document(
                            "\$not",
                            listOf(
                                Document(
                                    "\$and",
                                    listOf(
                                        Document("\$eq", listOf("\$\$b.$FIELD_RELATION_NAME", relationName)),
                                        Document("\$eq", listOf("\$\$b.$FIELD_RELATION_REF", ref)),
                                    ),
                                ),
                            ),
                        ),
                    ),
            )
        val stage =
            Document(
                "\$set",
                Document(FIELD_BACK_LINKS, Document("\$concatArrays", listOf(filtered, listOf(entry))))
                    .append(FIELD_HAS_DATA, Document("\$ifNull", listOf("\$$FIELD_HAS_DATA", false)))
                    .append(FIELD_TIMESTAMP, bumpTimestampIfHasData(timestamp)),
            )
        collection().updateOne(Document(FIELD_ID, resourceId), listOf(stage), UpdateOptions().upsert(true))
        bumpMeta(timestamp)
    }

    /**
     * Atomic back-link removal via an aggregation-pipeline update that filters the entry out by
     * `(relation, ref)`. No upsert, so it never creates a stub. Raises the timestamp only when the
     * resource already holds data, mirroring [addBackLink].
     */
    override fun removeBackLink(
        resourceId: String,
        relation: String,
        ref: String,
        timestamp: Long,
    ) {
        val filtered =
            Document(
                "\$filter",
                Document("input", Document("\$ifNull", listOf("\$$FIELD_BACK_LINKS", emptyList<Document>())))
                    .append("as", "b")
                    .append(
                        "cond",
                        Document(
                            "\$not",
                            listOf(
                                Document(
                                    "\$and",
                                    listOf(
                                        Document("\$eq", listOf("\$\$b.$FIELD_RELATION_NAME", relation.lowercase())),
                                        Document("\$eq", listOf("\$\$b.$FIELD_RELATION_REF", ref)),
                                    ),
                                ),
                            ),
                        ),
                    ),
            )
        val stage =
            Document(
                "\$set",
                Document(FIELD_BACK_LINKS, filtered)
                    .append(FIELD_TIMESTAMP, bumpTimestampIfHasData(timestamp)),
            )
        val result = collection().updateOne(Document(FIELD_ID, resourceId), listOf(stage))
        if (result.matchedCount > 0) bumpMeta(timestamp)
    }

    /**
     * Bulk back-link reconciliation: applies all [ops] across this collection in one `bulkWrite`,
     * reusing the same aggregation-pipeline stages as the single-op [addBackLink]/[removeBackLink].
     * Adds upsert a stub for an absent target; removes never create one.
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
                        addBackLinkStage(op.relation, op.link, timestamp)?.let { stage ->
                            UpdateOneModel<Document>(
                                Document(FIELD_ID, op.resourceId),
                                listOf(stage),
                                UpdateOptions().upsert(true),
                            )
                        }
                    }

                    is BackLinkOp.Remove -> {
                        UpdateOneModel<Document>(
                            Document(FIELD_ID, op.resourceId),
                            listOf(removeBackLinkStage(op.relation, op.ref, timestamp)),
                        )
                    }
                }
            }
        if (models.isEmpty()) return
        collection().bulkWrite(models, BulkWriteOptions().ordered(false))
        bumpMeta(timestamp)
    }

    /** `$set` stage appending [link]'s back-link entry, de-duplicated by `(relation, ref)`. */
    private fun addBackLinkStage(
        relation: String,
        link: Link,
        timestamp: Long,
    ): Document? {
        val entry = codec.linkEntry(relation, link) ?: return null
        return Document(
            "\$set",
            Document(
                FIELD_BACK_LINKS,
                Document(
                    "\$concatArrays",
                    listOf(
                        filterOutBackLink(entry.getString(FIELD_RELATION_NAME), entry.getString(FIELD_RELATION_REF)),
                        listOf(entry),
                    ),
                ),
            ).append(FIELD_HAS_DATA, Document("\$ifNull", listOf("\$$FIELD_HAS_DATA", false)))
                .append(FIELD_TIMESTAMP, bumpTimestampIfHasData(timestamp)),
        )
    }

    /** `$set` stage dropping the back-link matching `(relation, ref)`. */
    private fun removeBackLinkStage(
        relation: String,
        ref: String,
        timestamp: Long,
    ): Document =
        Document(
            "\$set",
            Document(FIELD_BACK_LINKS, filterOutBackLink(relation.lowercase(), ref))
                .append(FIELD_TIMESTAMP, bumpTimestampIfHasData(timestamp)),
        )

    /** `$filter` expression removing back-link entries matching `(relationName, ref)`. */
    private fun filterOutBackLink(
        relationName: String,
        ref: String,
    ): Document =
        Document(
            "\$filter",
            Document("input", Document("\$ifNull", listOf("\$$FIELD_BACK_LINKS", emptyList<Document>())))
                .append("as", "b")
                .append(
                    "cond",
                    Document(
                        "\$not",
                        listOf(
                            Document(
                                "\$and",
                                listOf(
                                    Document("\$eq", listOf("\$\$b.$FIELD_RELATION_NAME", relationName)),
                                    Document("\$eq", listOf("\$\$b.$FIELD_RELATION_REF", ref)),
                                ),
                            ),
                        ),
                    ),
                ),
        )

    /**
     * Pipeline expression raising the stored timestamp to [timestamp] only when the document already
     * holds data; stubs keep their (absent) timestamp so [put] can still detect and fill them.
     */
    private fun bumpTimestampIfHasData(timestamp: Long): Document =
        Document(
            "\$cond",
            listOf(
                Document("\$eq", listOf("\$$FIELD_HAS_DATA", true)),
                Document("\$max", listOf("\$$FIELD_TIMESTAMP", timestamp)),
                "\$$FIELD_TIMESTAMP",
            ),
        )

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
            return find.iterator().use { c ->
                val resources = ArrayList<FintResource>()
                while (c.hasNext()) {
                    resources.add(codec.fromDocument(c.next()))
                }
                resources
            }
        }

        return find.iterator().use { c ->
            val baseStream =
                StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(c, Spliterator.ORDERED or Spliterator.NONNULL),
                    false,
                )
            var resources: Stream<FintResource> = applyODataFilter(baseStream.map { codec.fromDocument(it) }, filter)
            if (size > 0) {
                if (offset > 0) {
                    resources = resources.skip(offset)
                }
                resources = resources.limit(size)
            }
            resources.toList()
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
        if (result.deletedCount > 0) bumpMeta(timestamp)
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
            bumpVersion()
        }
        return expired
    }

    companion object {
        const val META_COLLECTION = "cache_meta"
        const val FIELD_LAST_UPDATED = "lastUpdated"
        const val FIELD_VERSION = "version"
        private const val FIELD_MATCHED_REFS = "matchedRefs"
    }
}
