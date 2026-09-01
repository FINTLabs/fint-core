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
import no.novari.core.shared.kafka.EntityHeaders.SYNC_MARKER
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

        /**
         * Key prefix for sync markers. A resource key is the resource name, the delimiter and the
         * resource id, and no resource is named this, so a marker can never collide with one. The
         * corrId completes the key, which puts every sync's marker on its own key so log
         * compaction cannot drop one marker in favour of another.
         */
        const val SYNC_MARKER_KEY = "__sync-marker__"
    }

    val log = LoggerFactory.getLogger(BufferWriter::class.java)

    fun sendSyncEntity(
        syncPage: SyncPage,
        syncEntry: SyncPageEntry,
        coords: ResourceCoordinate,
    ): CompletableFuture<SendResult<String, Any>> =
        send(
            key = "${coords.resourceName}$KEY_DELIMITER${syncEntry.identifier}",
            coords = coords,
            resource = syncEntry.resource,
            lastModified = clock.millis(),
            syncMetadata = syncPage.toSyncMetadata(),
        )

    /**
     * Sends the record that stands in for a sync carrying no resources. It holds the same sync
     * headers an entity record holds, so the reader counts it against the same correlation id,
     * and its LAST_MODIFIED is what the eviction measures staleness against: everything written
     * before this moment is what the adapter has just said it no longer has.
     */
    fun sendSyncMarker(
        syncPage: SyncPage,
        coords: ResourceCoordinate,
    ): CompletableFuture<SendResult<String, Any>> =
        send(
            key = "$SYNC_MARKER_KEY$KEY_DELIMITER${syncPage.metadata.corrId}",
            coords = coords,
            resource = null,
            lastModified = clock.millis(),
            syncMetadata = syncPage.toSyncMetadata(),
            marker = true,
        )

    private fun send(
        key: String,
        coords: ResourceCoordinate,
        resource: Any?,
        lastModified: Long,
        syncMetadata: SyncMetadata?,
        marker: Boolean = false,
    ): CompletableFuture<SendResult<String, Any>> {
        log.debug("SEND TO KAFKA:: {}", resource)
        return kafkaTemplate.send(
            ProducerRecord<String, Any>(
                topic,
                key,
                resource,
            ).apply {
                headers().apply {
                    add(DOMAIN_NAME, coords.domainName.toByteArray())
                    add(ORG_ID, coords.orgId.toByteArray())
                    add(PACKAGE_NAME, coords.packageName.toByteArray())
                    add(RESOURCE_NAME, coords.resourceName.toByteArray())
                    add(LAST_MODIFIED, lastModified.toHeaderBytes())
                    if (marker) add(SYNC_MARKER, byteArrayOf(1))
                    syncMetadata?.let {
                        add(SYNC_TYPE, byteArrayOf(it.type.ordinal.toByte()))
                        add(SYNC_CORRELATION_ID, it.corrId.toByteArray())
                        add(SYNC_TOTAL_SIZE, it.totalSize.toHeaderBytes())
                    }
                }
            },
        )
    }

    private fun SyncPage.toSyncMetadata() =
        SyncMetadata(
            corrId = metadata.corrId,
            type = syncType,
            totalSize = metadata.totalSize,
        )
}
