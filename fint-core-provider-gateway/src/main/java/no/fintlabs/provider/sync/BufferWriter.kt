package no.fintlabs.provider.sync

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.sync.SyncPage
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.novari.core.shared.kafka.EntityHeaders.DOMAIN_NAME
import no.novari.core.shared.kafka.EntityHeaders.LAST_MODIFIED
import no.novari.core.shared.kafka.EntityHeaders.ORG_ID
import no.novari.core.shared.kafka.EntityHeaders.PACKAGE_NAME
import no.novari.core.shared.kafka.EntityHeaders.RESOURCE_NAME
import no.novari.core.shared.kafka.EntityHeaders.SYNC_CORRELATION_ID
import no.novari.core.shared.kafka.EntityHeaders.SYNC_TOTAL_SIZE
import no.novari.core.shared.kafka.EntityHeaders.SYNC_TYPE
import no.novari.core.shared.kafka.SyncMetadata
import no.novari.core.shared.kafka.toHeaderBytes
import no.novari.core.shared.model.ResourceCoordinate
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
    @param:Qualifier("topicBufferName") private val topic: String,
) {
    companion object {
        const val KEY_DELIMITER = "\u001F"
    }

    val log = LoggerFactory.getLogger(BufferWriter::class.java)

    fun sendSyncEntity(
        syncPage: SyncPage,
        syncEntry: SyncPageEntry,
        coords: ResourceCoordinate,
    ): CompletableFuture<SendResult<String, Any>> =
        send(
            coords,
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
        coords: ResourceCoordinate,
        syncEntry: SyncPageEntry,
        lastModified: Long,
    ): CompletableFuture<SendResult<String, Any>> =
        send(
            coords,
            resourceId = syncEntry.identifier,
            resource = syncEntry.resource,
            lastModified = lastModified,
            syncMetadata = null,
        )

    private fun send(
        coords: ResourceCoordinate,
        resourceId: String,
        resource: Any?,
        lastModified: Long,
        syncMetadata: SyncMetadata?,
    ): CompletableFuture<SendResult<String, Any>> {
        log.debug("SEND TO KAFKA:: {}", resource)
        return kafkaTemplate.send(
            ProducerRecord<String, Any>(
                topic,
                "${coords.resourceName}$KEY_DELIMITER$resourceId",
                resource,
            ).apply {
                headers().apply {
                    add(DOMAIN_NAME, coords.domainName.toByteArray())
                    add(ORG_ID, coords.orgId.toByteArray())
                    add(PACKAGE_NAME, coords.packageName.toByteArray())
                    add(RESOURCE_NAME, coords.resourceName.toByteArray())
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
}
