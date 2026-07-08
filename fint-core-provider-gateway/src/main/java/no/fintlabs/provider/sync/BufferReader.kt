package no.fintlabs.provider.sync

import com.fasterxml.jackson.databind.ObjectMapper
import no.novari.core.shared.kafka.EntityHeaders.DOMAIN_NAME
import no.novari.core.shared.kafka.EntityHeaders.ORG_ID
import no.novari.core.shared.kafka.EntityHeaders.PACKAGE_NAME
import no.novari.core.shared.kafka.EntityHeaders.RESOURCE_NAME
import no.novari.core.shared.kafka.stringValue
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.model.resource.FintResource
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
) {
    val log = LoggerFactory.getLogger(BufferReader::class.java)

    @KafkaListener(topics = ["#{topicBufferName}"], groupId = "consumer-service-group")
    fun readMessage(record: ConsumerRecord<String, String>) {
        log.debug("READ FROM KAFKA:: {}", record.value())

        // Convert to FintRecord
        processRecord(record)
    }

    private fun processRecord(record: ConsumerRecord<String, String>) {
        val coords = resourceCoordinate(record.headers())
        val payload = objectMapper.readValue(record.value(), Any::class.java)
        val resource =
            resourceConverter.convert(
                coords.domainName,
                coords.packageName,
                coords.resourceName,
                payload,
            )

        resourceStore.save(
            record.extractIdentifier(),
            coords,
            resource,
            Instant.now(),
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
