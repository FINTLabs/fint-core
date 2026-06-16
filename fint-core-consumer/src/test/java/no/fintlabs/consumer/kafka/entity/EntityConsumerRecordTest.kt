package no.fintlabs.consumer.kafka.entity

import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.consumer.kafka.KafkaConstants.LAST_MODIFIED
import no.fintlabs.consumer.kafka.KafkaConstants.SYNC_CORRELATION_ID
import no.fintlabs.consumer.kafka.KafkaConstants.SYNC_TOTAL_SIZE
import no.fintlabs.consumer.kafka.KafkaConstants.SYNC_TYPE
import no.novari.fint.model.resource.utdanning.vurdering.ElevfravarResource
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecord.NULL_SIZE
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.Optional
import java.util.UUID

class EntityConsumerRecordTest {
    @Test
    fun `fields are mapped correctly`() {
        val resourceKey = "test-key"
        val resourceName = "elevfravar"
        val resource = ElevfravarResource()
        val timestamp = System.currentTimeMillis()
        val syncCorrelationId = UUID.randomUUID().toString()
        val syncTotalSize = 100L
        val consumerRecord =
            createConsumerRecord(
                resourceKey,
                resource,
                timestamp,
                SyncType.DELTA.ordinal,
                syncCorrelationId,
                syncTotalSize,
            )
        val entityRecord = EntityConsumerRecord(resourceName, resource, consumerRecord)

        assertEquals(resourceName, entityRecord.resourceName)
        assertEquals(resourceKey, entityRecord.key)
        assertEquals(resource, entityRecord.resource)
        assertEquals(timestamp, entityRecord.timestamp)
        assertEquals(syncCorrelationId, entityRecord.corrId)
        assertEquals(syncTotalSize, entityRecord.totalSize)
        assertEquals(SyncType.DELTA, entityRecord.type)
    }

    @Test
    fun `fullSync type is set and converted`() {
        val consumerRecord = createConsumerRecord(syncTypeOrdinal = SyncType.FULL.ordinal)
        val entityRecord = EntityConsumerRecord("elevfravar", ElevfravarResource(), consumerRecord)

        assertEquals(SyncType.FULL, entityRecord.type)
    }

    @Test
    fun `deltaSync type is set and converted`() {
        val consumerRecord = createConsumerRecord(syncTypeOrdinal = SyncType.DELTA.ordinal)
        val entityRecord = EntityConsumerRecord("elevfravar", ElevfravarResource(), consumerRecord)

        assertEquals(SyncType.DELTA, entityRecord.type)
    }

    @Test
    fun `deleteSync type is set and converted`() {
        val consumerRecord = createConsumerRecord(syncTypeOrdinal = SyncType.DELETE.ordinal)
        val entityRecord = EntityConsumerRecord("elevfravar", ElevfravarResource(), consumerRecord)

        assertEquals(SyncType.DELETE, entityRecord.type)
    }

    @Test
    fun `unknown syncType byte value throws IllegalArgumentException`() {
        val consumerRecord = createConsumerRecord(syncTypeOrdinal = 123)

        assertThrows(IllegalArgumentException::class.java) {
            EntityConsumerRecord("elevfravar", ElevfravarResource(), consumerRecord)
        }
    }

    @Test
    fun `negative syncType index throws IllegalArgumentException`() {
        val consumerRecord = createConsumerRecord(syncTypeOrdinal = -5)

        assertThrows(IllegalArgumentException::class.java) {
            EntityConsumerRecord("elevfravar", ElevfravarResource(), consumerRecord)
        }
    }

    private fun createConsumerRecord(
        key: String = UUID.randomUUID().toString(),
        resource: ElevfravarResource = ElevfravarResource(),
        timestamp: Long = 0L,
        syncTypeOrdinal: Int?,
        syncCorrelationId: String? = null,
        syncTotalSize: Long? = null,
    ): ConsumerRecord<String, Any?> {
        val headers = RecordHeaders()
        val timestampBytes =
            ByteBuffer
                .allocate(Long.SIZE_BYTES)
                .putLong(timestamp)
                .array()
        headers.add(RecordHeader(LAST_MODIFIED, timestampBytes))
        if (syncTypeOrdinal != null) {
            headers.add(RecordHeader(SYNC_TYPE, byteArrayOf(syncTypeOrdinal.toByte())))
        }
        if (syncCorrelationId != null) {
            headers.add(RecordHeader(SYNC_CORRELATION_ID, syncCorrelationId.toByteArray()))
        }
        if (syncTotalSize != null) {
            headers.add(
                RecordHeader(
                    SYNC_TOTAL_SIZE,
                    ByteBuffer
                        .allocate(Long.SIZE_BYTES)
                        .putLong(syncTotalSize)
                        .array(),
                ),
            )
        }

        return ConsumerRecord<String, Any?>(
            "test-topic",
            0,
            0,
            timestamp,
            TimestampType.CREATE_TIME,
            NULL_SIZE,
            NULL_SIZE,
            key,
            resource,
            headers,
            Optional.empty<Int>(),
        )
    }
}
