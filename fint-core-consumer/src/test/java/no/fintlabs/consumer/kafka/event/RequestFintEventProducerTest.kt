package no.fintlabs.consumer.kafka.event

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.consumer.config.ConsumerConfiguration
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.util.concurrent.CompletableFuture

class RequestFintEventProducerTest {
    private val kafkaTemplate: KafkaTemplate<String, RequestFintEvent> = mockk()

    private val configuration =
        ConsumerConfiguration(
            baseUrl = "https://api.felleskomponent.no",
            orgIdValue = "novari.no",
            domain = "utdanning",
            packageName = "vurdering",
            podUrl = "http://localhost",
        )

    private val producer = RequestFintEventProducer(kafkaTemplate, configuration)

    @Test
    fun `publishes a sub-org event on the primary org's topic`() {
        every { kafkaTemplate.send(any<String>(), any(), any()) } returns
            CompletableFuture.completedFuture(mockk<SendResult<String, RequestFintEvent>>())

        val event =
            RequestFintEvent().apply {
                corrId = "corr-1"
                orgId = "test.novari.no"
            }

        producer.publish(event)

        verify {
            kafkaTemplate.send("novari-no.fint-core.fint-felleskomponent-event-request", "corr-1", event)
        }
    }
}
