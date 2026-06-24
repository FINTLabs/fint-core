package no.fintlabs.provider.buffer

import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.model.resource.FintResource
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class BufferReader(private val resourceStore: ResourceStore, private val resourceConverter: ResourceConverter) {

    val log = LoggerFactory.getLogger(BufferReader::class.java)

    @KafkaListener(topics = ["#{topicBufferName}"], groupId = "consumer-service-group")
    fun readMessage(record: ConsumerRecord<String, Any?>) {
        log.debug("READ FROM KAFKA:: {}", record.value())

        //Konventer til fint resource

        prossesRecord(record.value())
    }

    private fun prossesRecord(resource: FintResource) {
        resourceStore.save(
            resource.identifikators.toString(),
            resourceCoordinate(resource.selfLinks.first().toString()),
            resource,
            Instant.now()
        )
    }

    private fun resourceCoordinate(selfLink: String): ResourceCoordinate {
        return ResourceCoordinate()
    }

}