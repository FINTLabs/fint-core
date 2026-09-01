package no.fintlabs.provider.sync

import no.novari.core.shared.model.ResourceCoordinate
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.findById
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * Keeps the per-sync progress documents that let any replica finish a sync another replica
 * started. Everything here is one round trip, because it runs once per batch of buffered records.
 *
 * Abandoned syncs are left to a TTL index instead of being cleaned up by hand. A sync whose pages
 * stop arriving never reaches its total, so it never evicts, and its document simply expires.
 */
@Service
class SyncProgressStore(
    private val template: MongoTemplate,
) {
    init {
        template.indexOps(COLLECTION_NAME).createIndex(
            Index()
                .on("updatedAt", Sort.Direction.ASC)
                .named("sync_progress_ttl")
                .expire(TTL),
        )
    }

    fun find(corrId: String): SyncProgress? = template.findById<SyncProgress>(corrId, COLLECTION_NAME)

    /**
     * Adds [freshCount] records to a sync's progress and returns the updated document, creating
     * it if this is the first batch of the sync.
     *
     * [expectedOffset] is what the caller read from this partition's slot before deciding which
     * records were fresh, and the write only lands while that slot still holds it. Losing that
     * race, or racing another partition to create the document, throws
     * [org.springframework.dao.DuplicateKeyException] so the caller can read again and redecide.
     *
     * The offset slot moves with `$max` rather than a plain set, so a redelivery that starts
     * further back than what is already folded in cannot walk the watermark backwards and let the
     * same records be counted a second time.
     */
    fun fold(
        corrId: String,
        coordinate: ResourceCoordinate,
        totalSize: Long,
        partition: Int,
        expectedOffset: Long?,
        highestOffset: Long,
        freshCount: Int,
        startedAt: Instant,
    ): SyncProgress {
        val offsetField = "offsets.$partition"
        val identity = Criteria.where("_id").`is`(corrId)
        val query =
            Query.query(
                // An $exists match rather than an equality on null: Mongo builds an inserted
                // document out of the query's equalities, and a null one here would collide with
                // this update's own write to the same field.
                if (expectedOffset == null) {
                    identity.and(offsetField).exists(false)
                } else {
                    identity.and(offsetField).`is`(expectedOffset)
                },
            )

        val update =
            Update()
                .inc("processed", freshCount)
                .max(offsetField, highestOffset)
                .min("startedAt", startedAt)
                .currentDate("updatedAt")
                .setOnInsert("coordinate", coordinate)
                .setOnInsert("totalSize", totalSize)

        return template.findAndModify(
            query,
            update,
            FindAndModifyOptions().upsert(true).returnNew(true),
            SyncProgress::class.java,
            COLLECTION_NAME,
        )!!
    }

    /**
     * Takes the right to evict this sync, or returns null if it is already taken. Returning a
     * document exactly once is the whole point: several replicas can watch the same sync finish,
     * and a redelivered batch can make one replica watch it finish twice.
     */
    fun claimEviction(corrId: String): SyncProgress? =
        template.findAndModify(
            Query.query(
                Criteria
                    .where("_id")
                    .`is`(corrId)
                    .and("evictedAt")
                    .exists(false),
            ),
            Update().currentDate("evictedAt"),
            FindAndModifyOptions().returnNew(true),
            SyncProgress::class.java,
            COLLECTION_NAME,
        )

    companion object {
        const val COLLECTION_NAME = "sync_progress"
        private val TTL: Duration = Duration.ofHours(24)
    }
}
