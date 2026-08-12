package no.fintlabs.provider.sync

import com.fasterxml.jackson.databind.ObjectMapper
import no.fintlabs.provider.links.LinkService
import no.novari.core.shared.kafka.EntityHeaders.DOMAIN_NAME
import no.novari.core.shared.kafka.EntityHeaders.ORG_ID
import no.novari.core.shared.kafka.EntityHeaders.PACKAGE_NAME
import no.novari.core.shared.kafka.EntityHeaders.RESOURCE_NAME
import no.novari.core.shared.kafka.stringValue
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.ResourceStore
import no.novari.core.shared.store.ResourceWrite
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.Headers
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant

// READs Kafka buffer, and writes to database.
@Component
class BufferReader(
    private val resourceStore: ResourceStore,
    private val resourceConverter: ResourceConverter,
    private val objectMapper: ObjectMapper,
    private val linkService: LinkService,
) {
    val log = LoggerFactory.getLogger(BufferReader::class.java)

    @KafkaListener(
        topics = ["#{topicBufferName}"],
        groupId = "consumer-service-group",
        containerFactory = "bufferKafkaListenerContainerFactory",
    )
    fun readMessage(records: List<ConsumerRecord<String, String>>) {
        log.debug("Read {} records from Kafka buffer", records.size)

        // Convert all to ResourceWrite object
        val writes = records.map(::toResourceWrite)

        resourceStore.saveAll(writes)
    }

    /**
     * Converts a Kafka ConsumerRecord into a ResourceWrite object.
     *
     * @param record the Kafka consumer record containing the resource data and headers.
     * @return a ResourceWrite object representing the transformed resource.
     */
    private fun toResourceWrite(record: ConsumerRecord<String, String>): ResourceWrite {
        val coords = resourceCoordinate(record.headers())
        val payload = objectMapper.readValue(record.value(), Any::class.java)

        val resource =
            resourceConverter.convert(
                coords.domainName,
                coords.packageName,
                coords.resourceName,
                payload,
            )
        linkService.mapLinks(resource)

        return ResourceWrite(
            resourceId = record.extractIdentifier(),
            collectionName = coords.toCollectionName(),
            resource = resource,
            timestamp = Instant.now(),
        )
    }

    private fun resourceCoordinate(headers: Headers): ResourceCoordinate =
        ResourceCoordinate(
            orgId = headers.requiredStringValue(ORG_ID),
            domainName = headers.requiredStringValue(DOMAIN_NAME),
            packageName = headers.requiredStringValue(PACKAGE_NAME),
            resourceName = headers.requiredStringValue(RESOURCE_NAME),
        )

    private fun Headers.requiredStringValue(name: String): String =
        stringValue(name) ?: throw IllegalArgumentException("Missing required Kafka header '$name'")
}
