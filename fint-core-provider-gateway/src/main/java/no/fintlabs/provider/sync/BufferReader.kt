package no.fintlabs.provider.sync

import no.fintlabs.provider.storage.ResourceIngest
import no.fintlabs.provider.storage.ResourceWritePipeline
import no.novari.core.shared.json.FintJson
import no.novari.core.shared.kafka.EntityHeaders.DOMAIN_NAME
import no.novari.core.shared.kafka.EntityHeaders.LAST_MODIFIED
import no.novari.core.shared.kafka.EntityHeaders.ORG_ID
import no.novari.core.shared.kafka.EntityHeaders.PACKAGE_NAME
import no.novari.core.shared.kafka.EntityHeaders.RESOURCE_NAME
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

        val ingests =
            records.mapNotNull { record ->
                val json = record.value()
                if (json == null) {
                    // TODO: Since json is null we should delete it (tombstone)
                    log.warn("Skipping delition for key '{}' until the delete phase lands", record.key())
                    return@mapNotNull null
                }

                val coords = resourceCoordinate(record.headers())

                ResourceIngest(
                    coordinate = coords,
                    resourceId = record.extractIdentifier(),
                    resource = objectMapper.readValue(json, coords.toResourceClass()),
                    timestamp = record.headers().extractTimestamp(),
                )
            }

        resourceWritePipeline.applyAll(ingests)
    }

    private fun resourceCoordinate(headers: Headers): ResourceCoordinate =
        ResourceCoordinate(
            orgId = headers.requiredStringValue(ORG_ID),
            domainName = headers.requiredStringValue(DOMAIN_NAME),
            packageName = headers.requiredStringValue(PACKAGE_NAME),
            resourceName = headers.requiredStringValue(RESOURCE_NAME),
        )

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
