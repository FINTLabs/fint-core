package no.fintlabs.consumer.kafka.sync

import com.mongodb.client.model.UpdateOptions
import org.bson.Document
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Service

/**
 * Tracks the timestamp of the last completed full sync per resource. Backed by a shared Mongo
 * collection (keyed by resource name, monotonic `$max`) so the value is correct across replicas.
 */
@Service
class LastCompletedFullSyncCache(
    private val mongoTemplate: MongoTemplate,
) {
    private fun collection() = mongoTemplate.getCollection(COLLECTION)

    fun registerTimestamp(
        resourceName: String,
        timestamp: Long,
    ) {
        collection().updateOne(
            Document(FIELD_ID, resourceName),
            Document("\$max", Document(FIELD_TIMESTAMP, timestamp)),
            UpdateOptions().upsert(true),
        )
    }

    fun getLatestFromResource(resourceName: String): Long =
        collection()
            .find(Document(FIELD_ID, resourceName))
            .first()
            ?.getLong(FIELD_TIMESTAMP)
            ?: 0L

    companion object {
        const val COLLECTION = "sync_full_completed"
        private const val FIELD_ID = "_id"
        private const val FIELD_TIMESTAMP = "timestamp"
    }
}
