package no.novari.fint.core.shared.sync

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.UpdateOptions
import no.fintlabs.adapter.models.sync.SyncType
import org.bson.Document
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Component
import java.util.Date
import java.util.concurrent.TimeUnit

/** A persisted [SyncState] together with the optimistic-concurrency version it was read at. */
data class VersionedSyncState(
    val state: SyncState,
    val version: Long,
)

/**
 * Cross-replica store for per-correlation sync progress and per-resource active full-sync ownership.
 *
 * Progress is mutated with optimistic compare-and-set so any replica can advance a correlation's
 * [SyncState] machine without a distributed lock; the caller retries on a lost race. Stale entries
 * are reaped by a TTL index rather than an explicit expiry notification.
 */
interface SyncProgressStore {
    fun read(correlationId: String): VersionedSyncState?

    /**
     * Persist [newState] for [correlationId]. When [expectedVersion] is `null` the write must be an
     * insert (fails if the document already exists); otherwise it succeeds only if the stored version
     * still equals [expectedVersion]. Returns `true` when the write was applied.
     */
    fun compareAndSet(
        correlationId: String,
        expectedVersion: Long?,
        newState: SyncState,
    ): Boolean

    fun delete(correlationId: String)

    /**
     * Record [correlationId] as the resource's active full sync, returning the correlation id that
     * held it before (or `null` if none). A returned id different from [correlationId] signals a
     * concurrent full sync of the same resource.
     */
    fun claimActiveFullSync(
        resourceName: String,
        correlationId: String,
    ): String?

    fun clearActiveFullSync(
        resourceName: String,
        correlationId: String,
    )
}

@Component
class MongoSyncProgressStore(
    private val mongoTemplate: MongoTemplate,
) : SyncProgressStore {
    init {
        ensureIndexes()
    }

    private fun progress() = mongoTemplate.getCollection(PROGRESS_COLLECTION)

    private fun activeFull() = mongoTemplate.getCollection(ACTIVE_FULL_COLLECTION)

    private fun ensureIndexes() {
        progress().createIndex(
            Indexes.ascending(FIELD_UPDATED_AT),
            IndexOptions().name("progress_ttl_idx").expireAfter(TTL_SECONDS, TimeUnit.SECONDS),
        )
        activeFull().createIndex(
            Indexes.ascending(FIELD_UPDATED_AT),
            IndexOptions().name("active_full_ttl_idx").expireAfter(TTL_SECONDS, TimeUnit.SECONDS),
        )
    }

    override fun read(correlationId: String): VersionedSyncState? {
        val doc = progress().find(Document(FIELD_ID, correlationId)).first() ?: return null
        val state =
            SyncState.rebuild(
                kind = SyncKind.valueOf(doc.getString(FIELD_KIND)),
                resourceName = doc.getString(FIELD_RESOURCE_NAME),
                timestamp = doc.getLong(FIELD_TIMESTAMP),
                totalSize = doc.getLong(FIELD_TOTAL_SIZE),
                processedCount = doc.getLong(FIELD_PROCESSED_COUNT),
                syncType = SyncType.valueOf(doc.getString(FIELD_SYNC_TYPE)),
                description = doc.getString(FIELD_DESCRIPTION) ?: "",
            )
        return VersionedSyncState(state, doc.getLong(FIELD_VERSION))
    }

    override fun compareAndSet(
        correlationId: String,
        expectedVersion: Long?,
        newState: SyncState,
    ): Boolean =
        if (expectedVersion == null) {
            try {
                progress().insertOne(stateDocument(correlationId, newState, version = 0L))
                true
            } catch (e: MongoWriteException) {
                if (ErrorCategory.fromErrorCode(e.code) == ErrorCategory.DUPLICATE_KEY) false else throw e
            }
        } else {
            val result =
                progress().updateOne(
                    Document(FIELD_ID, correlationId).append(FIELD_VERSION, expectedVersion),
                    Document("\$set", stateFields(newState).append(FIELD_VERSION, expectedVersion + 1)),
                )
            result.modifiedCount == 1L
        }

    override fun delete(correlationId: String) {
        progress().deleteOne(Document(FIELD_ID, correlationId))
    }

    override fun claimActiveFullSync(
        resourceName: String,
        correlationId: String,
    ): String? {
        val previous =
            activeFull().findOneAndUpdate(
                Document(FIELD_ID, resourceName),
                Document(
                    "\$set",
                    Document(FIELD_CORRELATION_ID, correlationId).append(FIELD_UPDATED_AT, Date()),
                ),
                com.mongodb.client.model
                    .FindOneAndUpdateOptions()
                    .upsert(true)
                    .returnDocument(ReturnDocument.BEFORE),
            )
        return previous?.getString(FIELD_CORRELATION_ID)
    }

    override fun clearActiveFullSync(
        resourceName: String,
        correlationId: String,
    ) {
        activeFull().deleteOne(
            Document(FIELD_ID, resourceName).append(FIELD_CORRELATION_ID, correlationId),
        )
    }

    private fun stateDocument(
        correlationId: String,
        state: SyncState,
        version: Long,
    ): Document = stateFields(state).append(FIELD_ID, correlationId).append(FIELD_VERSION, version)

    private fun stateFields(state: SyncState): Document =
        Document(FIELD_KIND, state.kind.name)
            .append(FIELD_RESOURCE_NAME, state.resourceName)
            .append(FIELD_TIMESTAMP, state.timestamp)
            .append(FIELD_TOTAL_SIZE, state.totalSize)
            .append(FIELD_PROCESSED_COUNT, state.processedCount)
            .append(FIELD_SYNC_TYPE, state.syncType.name)
            .append(FIELD_DESCRIPTION, state.description)
            .append(FIELD_UPDATED_AT, Date())

    companion object {
        const val PROGRESS_COLLECTION = "sync_progress"
        const val ACTIVE_FULL_COLLECTION = "sync_active_full"
        private const val TTL_SECONDS = 24L * 60 * 60

        private const val FIELD_ID = "_id"
        private const val FIELD_VERSION = "version"
        private const val FIELD_KIND = "kind"
        private const val FIELD_RESOURCE_NAME = "resourceName"
        private const val FIELD_TIMESTAMP = "timestamp"
        private const val FIELD_TOTAL_SIZE = "totalSize"
        private const val FIELD_PROCESSED_COUNT = "processedCount"
        private const val FIELD_SYNC_TYPE = "syncType"
        private const val FIELD_DESCRIPTION = "description"
        private const val FIELD_UPDATED_AT = "updatedAt"
        private const val FIELD_CORRELATION_ID = "correlationId"
    }
}
