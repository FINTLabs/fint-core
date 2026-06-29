package no.fintlabs.provider.buffer

import no.novari.core.shared.kafka.EntityHeaders.DOMAIN_NAME
import no.novari.core.shared.kafka.EntityHeaders.ORG_ID
import no.novari.core.shared.kafka.EntityHeaders.PACKAGE_NAME
import no.novari.core.shared.kafka.EntityHeaders.RESOURCE_NAME
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
class BufferReader(private val resourceStore: ResourceStore, private val resourceConverter: ResourceConverter) {

    val log = LoggerFactory.getLogger(BufferReader::class.java)

    @KafkaListener(topics = ["#{topicBufferName}"], groupId = "consumer-service-group")
    fun readMessage(record: ConsumerRecord<String, FintResource>) {
        log.debug("READ FROM KAFKA:: {}", record.value())

        //Convert to FintRecord
        processRecord(record)
    }

    private fun processRecord(record: ConsumerRecord<String, FintResource>) {
        resourceStore.save(
            record.value().identifikators.toString(),
            resourceCoordinate(record.headers()),
            record.value(),
            Instant.now()
        )
    }

    // To set destination for a kafka message. For example utdanning-vurdering
    private fun resourceCoordinate(headers: Headers): ResourceCoordinate {
        return ResourceCoordinate(
            headers.lastHeader(ORG_ID).toString(),
            headers.lastHeader(PACKAGE_NAME).toString(),
            headers.lastHeader(DOMAIN_NAME).toString(),
            headers.lastHeader(RESOURCE_NAME).toString()

        )
    }

}