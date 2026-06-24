package no.fintlabs.provider.buffer

import org.springframework.kafka.annotation.KafkaListener

class BufferReader {

    @KafkaListener(topics = ["#{topicBufferName}"], groupId = "consumer-service-group")
    fun readMessage(message: String) {
        println("Received message: $message")
    }

}