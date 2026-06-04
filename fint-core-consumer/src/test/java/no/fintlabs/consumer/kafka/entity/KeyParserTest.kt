package no.fintlabs.consumer.kafka.entity

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID

class KeyParserTest {
    @Test
    fun `extractIdentifier returns identifier from delimited key`() {
        val record = createRecord("elevfravar${ENTITY_KEY_DELIMITER}abc-123")

        assertEquals("abc-123", record.extractIdentifier())
    }

    @Test
    fun `extractIdentifier returns full key when no delimiter present`() {
        val legacyKey = UUID.randomUUID().toString()
        val record = createRecord(legacyKey)

        assertEquals(legacyKey, record.extractIdentifier())
    }

    @Test
    fun `extractIdentifier handles identifier containing special characters`() {
        val record = createRecord("student${ENTITY_KEY_DELIMITER}some_weird-id.with" + "stuff")

        assertEquals("some_weird-id.withstuff", record.extractIdentifier())
    }

    private fun createRecord(key: String): ConsumerRecord<String, Any?> =
        ConsumerRecord(
            "test-topic",
            0,
            0,
            0L,
            TimestampType.CREATE_TIME,
            0,
            0,
            key,
            null,
            RecordHeaders(),
            Optional.empty(),
        )
}
