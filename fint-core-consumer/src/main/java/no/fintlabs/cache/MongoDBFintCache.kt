package no.fintlabs.cache

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOptions
import no.fint.antlr.odata.ODataFilterService
import no.fintlabs.cache.CacheDocumentCodec.Companion.FIELD_BACK_LINKS
import no.fintlabs.cache.CacheDocumentCodec.Companion.FIELD_HAS_DATA
import no.fintlabs.cache.CacheDocumentCodec.Companion.FIELD_ID
import no.fintlabs.cache.CacheDocumentCodec.Companion.FIELD_IDENTIFIERS
import no.fintlabs.cache.CacheDocumentCodec.Companion.FIELD_IDENTIFIER_KEY
import no.fintlabs.cache.CacheDocumentCodec.Companion.FIELD_IDENTIFIER_VALUE
import no.fintlabs.cache.CacheDocumentCodec.Companion.FIELD_RELATION_NAME
import no.fintlabs.cache.CacheDocumentCodec.Companion.FIELD_RELATION_REF
import no.fintlabs.cache.CacheDocumentCodec.Companion.FIELD_TIMESTAMP
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link
import org.bson.Document
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.Spliterator
import java.util.Spliterators
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

    init {
        ensureIndexes()
    }

    private fun collection(): MongoCollection<Document> = mongoTemplate.getCollection(collectionName)

    /**
     * Tracks the timestamp of the last accepted mutation (put or remove) for this collection,
     * including removals — which leave no document behind and so cannot be recovered from the data.
     * A single shared `cache_meta` document keyed by collection name keeps this cross-replica
     * correct; the monotonic `$max` makes concurrent and out-of-order bumps idempotent.
     */
    private fun bumpLastUpdated(timestamp: Long) {
        mongoTemplate.getCollection(META_COLLECTION).updateOne(
            Document(FIELD_ID, collectionName),
            Document("\$max", Document(FIELD_LAST_UPDATED, timestamp)),
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
            bumpLastUpdated(timestamp)
            true
        } catch (e: MongoWriteException) {
            if (ErrorCategory.fromErrorCode(e.code) == ErrorCategory.DUPLICATE_KEY) false else throw e
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
        bumpLastUpdated(timestamp)
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
        if (result.matchedCount > 0) bumpLastUpdated(timestamp)
    }

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
     * When [filter] is supplied the cursor is streamed and filtering is applied in-app via
     * [ODataFilterService] before pagination so OData semantics remain unchanged.
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
        val cursor =
            collection()
                .find(criteria)
                .sort(Sorts.ascending(FIELD_TIMESTAMP, FIELD_ID))
                .iterator()

        return cursor.use { c ->
            val baseStream =
                StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(c, Spliterator.ORDERED or Spliterator.NONNULL),
                    false,
                )

            var resources: Stream<FintResource> = baseStream.map { codec.fromDocument(it) }
            if (!filter.isNullOrBlank()) {
                resources = applyODataFilter(resources, filter)
            }
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

    override val size: Int
        get() = collection().countDocuments(Document(FIELD_HAS_DATA, true)).toInt()

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
            bumpLastUpdated(timestamp)
        }
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
        }
        return expired
    }

    companion object {
        const val META_COLLECTION = "cache_meta"
        const val FIELD_LAST_UPDATED = "lastUpdated"
    }
}
