package no.fintlabs.provider.datasync

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.sync.SyncPage
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.sync.SyncPageMetadata
import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.provider.buffer.BufferWriter
import no.fintlabs.provider.buffer.BufferWriter.Companion.KEY_DELIMITER
import no.fintlabs.provider.config.ProviderProperties
import no.novari.core.shared.kafka.EntityHeaders.LAST_MODIFIED
import no.novari.core.shared.kafka.EntityHeaders.RESOURCE_NAME
import no.novari.core.shared.kafka.EntityHeaders.SYNC_CORRELATION_ID
import no.novari.core.shared.kafka.EntityHeaders.SYNC_TOTAL_SIZE
import no.novari.core.shared.kafka.EntityHeaders.SYNC_TYPE
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture

class EntityProducerTest {
    private val orgId = "fintlabs.no"
    private val expectedTopic = "fintlabs-no.fint-felleskomponent-resource"

    private lateinit var kafkaTemplate: KafkaTemplate<String, Any>
    private lateinit var clock: Clock
    private lateinit var sut: BufferWriter

    @BeforeEach
    fun setup() {
        kafkaTemplate = mockk()
        clock = mockk()

        val providerProperties = ProviderProperties(orgIdValue = orgId)

        sut = BufferWriter(kafkaTemplate, clock, expectedTopic)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `sendSyncEntity builds expected topic and headers`() {
        val expectedLastModified = 1337L
        val expectedSyncType = SyncType.FULL
        val expectedSyncCorrId = UUID.randomUUID().toString()
        val expectedSyncTotalSize = 9L
        val expectedResourceName = "student"

        val syncPage =
            SyncPage(expectedSyncType).apply {
                metadata =
                    SyncPageMetadata().apply {
                        orgId = "fintlabs.no"
                        corrId = expectedSyncCorrId
                        uriRef = "utdanning/elev/$expectedResourceName"
                        totalSize = expectedSyncTotalSize
                    }
            }
        val entry =
            SyncPageEntry().apply {
                identifier = UUID.randomUUID().toString()
                resource = mapOf("id" to 42)
            }

        every { clock.millis() } returns expectedLastModified

        val record = sendAndCapture { sut.sendSyncEntity(syncPage, entry) }

        assertEquals(expectedTopic, record.topic())
        assertEquals(expectedLastModified, record.headerValue(LAST_MODIFIED).long())
        assertEquals(expectedSyncType.ordinal.toByte(), record.headerValue(SYNC_TYPE).first())
        assertEquals(expectedSyncCorrId, record.headerValue(SYNC_CORRELATION_ID).toString(Charset.defaultCharset()))
        assertEquals(expectedResourceName, record.headerValue(RESOURCE_NAME).toString(Charset.defaultCharset()))
        assertEquals(expectedSyncTotalSize, record.headerValue(SYNC_TOTAL_SIZE).long())
        assertEquals("$expectedResourceName$KEY_DELIMITER${entry.identifier}", record.key())
        assertEquals(entry.resource, record.value())
    }

    @Test
    fun `sendEventEntity builds expected topic and headers`() {
        val expectedLastModified = 133710428L
        val expectedResourceName = "elevfravar"
        val request =
            RequestFintEvent().apply {
                orgId = "fintlabs.no"
                domainName = "utdanning"
                packageName = "vurdering"
                resourceName = expectedResourceName
            }
        val entry =
            SyncPageEntry().apply {
                identifier = UUID.randomUUID().toString()
                resource = mapOf("id" to 42)
            }

        val record = sendAndCapture { sut.sendEventEntity(request, entry, expectedLastModified) }

        assertEquals(expectedTopic, record.topic())
        assertEquals(expectedLastModified, record.headerValue(LAST_MODIFIED).long())
        assertEquals(expectedResourceName, record.headerValue(RESOURCE_NAME).toString(Charset.defaultCharset()))
        assertNull(record.headers().lastHeader(SYNC_TYPE))
        assertNull(record.headers().lastHeader(SYNC_CORRELATION_ID))
        assertNull(record.headers().lastHeader(SYNC_TOTAL_SIZE))
        assertEquals("$expectedResourceName$KEY_DELIMITER${entry.identifier}", record.key())
        assertEquals(entry.resource, record.value())
    }

    private fun ByteArray.long(): Long = ByteBuffer.wrap(this).long

    private fun ProducerRecord<String, Any>.headerValue(key: String) = this.headers().lastHeader(key).value()

    private fun sendAndCapture(block: () -> Unit): ProducerRecord<String, Any> {
        val slot = slot<ProducerRecord<String, Any>>()
        every { kafkaTemplate.send(capture(slot)) } answers {
            CompletableFuture.completedFuture(mockk<SendResult<String, Any>>(relaxed = true))
        }
        block()
        return slot.captured
    }
}
