package no.fintlabs.provider.buffer

import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class BufferReader {

    @KafkaListener(topics = ["#{topicBufferName}"], groupId = "consumer-service-group")
    fun readMessage(string: String) {
        println("READ FROM KAFKA::" + string)
    }

}