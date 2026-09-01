package no.fintlabs.provider.sync

import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.provider.storage.ResourceIngest
import no.fintlabs.provider.storage.ResourceWritePipeline
import no.novari.core.shared.json.FintJson
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
import no.novari.core.shared.kafka.byteValue
import no.novari.core.shared.kafka.longValue
import no.novari.core.shared.kafka.stringValue
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.model.toResourceClass
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.Headers
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class BufferReader(
    private val resourceWritePipeline: ResourceWritePipeline,
    private val syncCompletionTracker: SyncCompletionTracker,
) {
    val log: Logger = LoggerFactory.getLogger(BufferReader::class.java)
    private val objectMapper = FintJson.storageMapper()

    @KafkaListener(
        topics = ["#{topicBufferName}"],
        groupId = "consumer-service-group",
        containerFactory = "bufferKafkaListenerContainerFactory",
    )
    fun readMessage(records: List<ConsumerRecord<String, String>>) {
        log.debug("Read {} records from Kafka buffer", records.size)

        val buffered = records.map { it.toBufferedRecord() }

        resourceWritePipeline.applyAll(buffered.mapNotNull { it.ingest })
        syncCompletionTracker.track(buffered.mapNotNull { it.sync })
    }

    /**
     * A record's two readings: what to write, and what it says about the sync it belongs to.
     * Both are optional. A marker carries no resource, and a record produced outside a sync, such
     * as a hand-built test record, belongs to no sync.
     */
    private data class BufferedRecord(
        val ingest: ResourceIngest?,
        val sync: SyncRecord?,
    )

    /**
     * A sync reading is only produced for a record that is also being written, or for a marker,
     * which stands in for a sync that has nothing to write. That keeps counting records and
     * counting what reached storage the same thing, which is what lets a finished count mean the
     * sync is safe to evict against.
     */
    private fun ConsumerRecord<String, String>.toBufferedRecord(): BufferedRecord {
        val headers = headers()
        val coordinate = headers.toResourceCoordinate()

        if (headers.isSyncMarker()) return BufferedRecord(ingest = null, sync = toSyncRecord(coordinate))

        val json = value()
        if (json == null) {
            // TODO: Since json is null we should delete it (tombstone)
            log.warn("Skipping delition for key '{}' until the delete phase lands", key())
            return BufferedRecord(ingest = null, sync = null)
        }

        val ingest =
            ResourceIngest(
                coordinate = coordinate,
                resourceId = extractIdentifier(),
                resource = objectMapper.readValue(json, coordinate.toResourceClass()),
                timestamp = headers.extractTimestamp(),
            )

        return BufferedRecord(ingest = ingest, sync = toSyncRecord(coordinate))
    }

    private fun ConsumerRecord<String, String>.toSyncRecord(coordinate: ResourceCoordinate): SyncRecord? =
        headers().toSyncMetadata()?.let {
            SyncRecord(
                coordinate = coordinate,
                metadata = it,
                partition = partition(),
                offset = offset(),
                writtenAt = headers().extractTimestamp(),
            )
        }

    private fun Headers.toResourceCoordinate(): ResourceCoordinate =
        ResourceCoordinate(
            orgId = requiredStringValue(ORG_ID),
            domainName = requiredStringValue(DOMAIN_NAME),
            packageName = requiredStringValue(PACKAGE_NAME),
            resourceName = requiredStringValue(RESOURCE_NAME),
        )

    /**
     * The sync headers [BufferWriter] stamps on every record that came from a sync page, or null
     * on a record that did not. The sync type travels as its ordinal, so the order of [SyncType]
     * is part of the wire format and reordering it would change what old records mean.
     */
    private fun Headers.toSyncMetadata(): SyncMetadata? {
        val corrId = stringValue(SYNC_CORRELATION_ID) ?: return null
        val type = byteValue(SYNC_TYPE)?.toInt()?.let(SyncType.entries::getOrNull) ?: return null
        val totalSize = longValue(SYNC_TOTAL_SIZE) ?: return null

        return SyncMetadata(corrId = corrId, type = type, totalSize = totalSize)
    }

    private fun Headers.isSyncMarker(): Boolean = byteValue(SYNC_MARKER) != null

    /**
     * When the write happened, read from the LAST_MODIFIED header that [BufferWriter] stamps on
     * every record it produces (the provider's clock at sync-page receipt). It is only missing
     * on records that did not come from [BufferWriter], such as hand-built test records or a
     * record with a broken header. The now() fallback makes such a write count as the newest,
     * so the resource store's check against older writes always lets it through.
     */
    private fun Headers.extractTimestamp(): Instant = longValue(LAST_MODIFIED)?.let(Instant::ofEpochMilli) ?: Instant.now()

    private fun Headers.requiredStringValue(name: String): String =
        stringValue(name) ?: throw IllegalArgumentException("Missing required Kafka header '$name'")
}
