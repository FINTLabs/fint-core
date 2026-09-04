package no.fintlabs.provider.kafka

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import no.fintlabs.provider.config.ProviderProperties
import org.apache.kafka.clients.producer.ProducerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

class EventPublisherTest {
    private val kafkaTemplate: KafkaTemplate<String, Any> = mockk()
    private val record = slot<ProducerRecord<String, Any>>()
    private val publisher =
        EventPublisher(
            kafkaTemplate,
            ProviderProperties(orgIdValue = "fintlabs.no", baseUrl = ""),
            "provider-test",
        )

    init {
        every { kafkaTemplate.send(capture(record)) } returns
            CompletableFuture.completedFuture(mockk<SendResult<String, Any>>())
    }

    @Test
    fun `publishes to the legacy event topic with key and origin header`() {
        publisher.publish("provider-error", "error-id", "payload")

        with(record.captured) {
            assertThat(topic()).isEqualTo("fintlabs-no.fint-core.event.provider-error")
            assertThat(key()).isEqualTo("error-id")
            assertThat(value()).isEqualTo("payload")
            assertThat(
                headers().lastHeader("origin.application.id").value().toString(StandardCharsets.UTF_8),
            ).isEqualTo("provider-test")
        }
    }
}
