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
