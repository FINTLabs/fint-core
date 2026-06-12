package no.novari.fint.core.provider.datasync.ingest

import com.mongodb.MongoException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.adapter.models.sync.SyncType
import no.novari.fint.core.provider.datasync.ResourceCacheWriter
import no.novari.fint.core.provider.datasync.SyncCompletionTracker
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.util.concurrent.CompletableFuture

class SyncIngestListenerTest {
    private val resourceCacheWriter = mockk<ResourceCacheWriter>(relaxed = true)
    private val syncCompletionTracker = mockk<SyncCompletionTracker>(relaxed = true)
    private val kafkaTemplate = mockk<KafkaTemplate<String, SyncIngestRecord>>()
    private val topics = SyncIngestTopics("fintlabs-no", "fint-core")

    private val listener =
        SyncIngestListener(resourceCacheWriter, syncCompletionTracker, kafkaTemplate, topics, SimpleMeterRegistry())

    @Test
    fun `a mongo failure rethrows so the container retries the batch and nothing is dead-lettered`() {
        every { resourceCacheWriter.writeBatch(any(), any(), any()) } throws MongoException("mongo down")

        assertThrows(MongoException::class.java) { listener.onBatch(records("a", "b")) }

        verify(exactly = 0) { kafkaTemplate.send(any<String>(), any(), any()) }
        verify(exactly = 0) { syncCompletionTracker.track(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a spring data access failure also rethrows for batch retry`() {
        every { resourceCacheWriter.writeBatch(any(), any(), any()) } throws DataAccessResourceFailureException("blip")

        assertThrows(DataAccessResourceFailureException::class.java) { listener.onBatch(records("a")) }

        verify(exactly = 0) { kafkaTemplate.send(any<String>(), any(), any()) }
    }

    @Test
    fun `a deterministic failure salvages the batch per record and dead-letters only the poison one`() {
        every { resourceCacheWriter.writeBatch(any(), any(), any()) } throws IllegalArgumentException("unconvertible")
        every { resourceCacheWriter.write(any(), eq("good"), any(), any()) } returns Unit
        every { resourceCacheWriter.write(any(), eq("poison"), any(), any()) } throws IllegalArgumentException("unconvertible")
        every { kafkaTemplate.send(any<String>(), any(), any()) } returns
            CompletableFuture.completedFuture(mockk<SendResult<String, SyncIngestRecord>>())

        listener.onBatch(records("good", "poison"))

        verify(exactly = 1) { resourceCacheWriter.write(RESOURCE_KEY, "good", any(), any()) }
        verify(exactly = 1) { kafkaTemplate.send(topics.dlt, "$RESOURCE_KEY:poison", any()) }
        verify(exactly = 1) { syncCompletionTracker.track(RESOURCE_KEY, CORR_ID, 2, any(), 2) }
    }

    private fun records(vararg identifiers: String): List<ConsumerRecord<String, SyncIngestRecord?>> =
        identifiers.mapIndexed { index, identifier ->
            ConsumerRecord<String, SyncIngestRecord?>(
                topics.topic,
                0,
                index.toLong(),
                "$RESOURCE_KEY:$identifier",
                SyncIngestRecord(
                    resourceKey = RESOURCE_KEY,
                    identifier = identifier,
                    orgId = "test.org.no",
                    corrId = CORR_ID,
                    syncType = SyncType.FULL,
                    totalSize = identifiers.size.toLong(),
                    time = TIME,
                    resource = mapOf("systemId" to mapOf("identifikatorverdi" to identifier)),
                ),
            )
        }

    companion object {
        private const val RESOURCE_KEY = "utdanning_elev_elev"
        private const val CORR_ID = "corr-1"
        private const val TIME = 1_000L
    }
}
