package no.fintlabs.consumer.kafka.entity

import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.consumer.kafka.KafkaConstants.LAST_MODIFIED
import no.fintlabs.consumer.kafka.KafkaConstants.SYNC_CORRELATION_ID
import no.fintlabs.consumer.kafka.KafkaConstants.SYNC_TOTAL_SIZE
import no.fintlabs.consumer.kafka.KafkaConstants.SYNC_TYPE
import no.fintlabs.consumer.kafka.byteValue
import no.fintlabs.consumer.kafka.longValue
import no.fintlabs.consumer.kafka.stringValue
import no.novari.fint.model.resource.FintResource
import org.apache.kafka.clients.consumer.ConsumerRecord

/**
 * Represents a FINT entity consumer record.
 *
 * This class collects all relevant fields from the Kafka [ConsumerRecord] (key, headers,
 * resource payload, and optional sync metadata) so they can be handled as one
 * cohesive object instead of spreading raw Kafka details throughout the codebase.
 *
 * - `resource` is nullable: a `null` value indicates the entity is being deleted.
 * - `type` is nullable: not all entities participate in sync operations.
 */
class EntityConsumerRecord(
    val resourceName: String,
    val resource: FintResource?,
    record: ConsumerRecord<String, Any?>,
) {
    val key: String = record.extractIdentifier()
    val timestamp =
        record.headers().longValue(LAST_MODIFIED)
            ?: throw NullPointerException("Required '$LAST_MODIFIED' header is missing")
    val type = record.headers().byteValue(SYNC_TYPE)?.let { syncType(it) }
    val corrId = record.headers().stringValue(SYNC_CORRELATION_ID)
    val totalSize = record.headers().longValue(SYNC_TOTAL_SIZE)
}

private fun syncType(value: Byte?) =
    value
        ?.let { SyncType.entries.getOrNull(it.toInt()) }
        ?: throw IllegalArgumentException("Invalid SyncType value: $value")
