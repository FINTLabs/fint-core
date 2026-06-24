package no.fintlabs.provider.buffer

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.sync.SyncPage
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.provider.config.ProviderProperties
import no.novari.core.shared.kafka.EntityHeaders.LAST_MODIFIED
import no.novari.core.shared.kafka.EntityHeaders.RESOURCE_NAME
import no.novari.core.shared.kafka.EntityHeaders.SYNC_CORRELATION_ID
import no.novari.core.shared.kafka.EntityHeaders.SYNC_TOTAL_SIZE
import no.novari.core.shared.kafka.EntityHeaders.SYNC_TYPE
import no.novari.core.shared.kafka.SyncMetadata
import no.novari.core.shared.kafka.toHeaderBytes
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.CompletableFuture

@Component
class BufferWriter(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val clock: Clock,
    @Qualifier("topicBufferName") private val topic: String
) {

    companion object {
        const val KEY_DELIMITER = "\u001F"
    }

    val log = LoggerFactory.getLogger(BufferWriter::class.java)

    fun sendSyncEntity(
        syncPage: SyncPage,
        syncEntry: SyncPageEntry,
    ): CompletableFuture<SendResult<String, Any>> =
        send(
            resourceName = syncPage.getResourceName(),
            resourceId = syncEntry.identifier,
            resource = syncEntry.resource,
            lastModified = clock.millis(),
            syncMetadata =
                SyncMetadata(
                    corrId = syncPage.metadata.corrId,
                    type = syncPage.syncType,
                    totalSize = syncPage.metadata.totalSize,
                ),
        )

    fun sendEventEntity(
        request: RequestFintEvent,
        syncEntry: SyncPageEntry,
        lastModified: Long,
    ): CompletableFuture<SendResult<String, Any>> =
        send(
            resourceName = request.resourceName,
            resourceId = syncEntry.identifier,
            resource = syncEntry.resource,
            lastModified = lastModified,
            syncMetadata = null,
        )

    private fun send(
        resourceName: String,
        resourceId: String,
        resource: Any?,
        lastModified: Long,
        syncMetadata: SyncMetadata?,
    ): CompletableFuture<SendResult<String, Any>> {
        log.debug("SEND TO KAFKA:: {}", resource)
        kafkaTemplate.send(
            ProducerRecord<String, Any>(
                topic,
                "$resourceName$KEY_DELIMITER$resourceId",
                resource,
            ).apply {
                headers().apply {
                    add(RESOURCE_NAME, resourceName.toByteArray())
                    add(LAST_MODIFIED, lastModified.toHeaderBytes())
                    syncMetadata?.let {
                        add(SYNC_TYPE, byteArrayOf(it.type.ordinal.toByte()))
                        add(SYNC_CORRELATION_ID, it.corrId.toByteArray())
                        add(SYNC_TOTAL_SIZE, it.totalSize.toHeaderBytes())
                    }
                }
            },
        )
    }

    private fun SyncPage.getResourceName() = metadata.uriRef.split("/").last()
}
