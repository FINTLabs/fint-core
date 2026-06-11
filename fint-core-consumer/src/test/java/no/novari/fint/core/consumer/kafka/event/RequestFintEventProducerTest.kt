package no.novari.fint.core.consumer.kafka.event

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.novari.fint.core.consumer.config.ConsumerConfiguration
import no.novari.fint.core.consumer.config.OrgId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate

class RequestFintEventProducerTest {
    private val config = mockk<ConsumerConfiguration>()
    private val kafkaTemplate = mockk<KafkaTemplate<String, Any>>(relaxed = true)

    private lateinit var producer: RequestFintEventProducer

    @BeforeEach
    fun setUp() {
        every { config.orgId } returns OrgId.from("fintlabs.no")

        producer = RequestFintEventProducer(kafkaTemplate, config, "fint-core")
    }

    @Test
    fun `publish sends event with corrId as key`() {
        val event = RequestFintEvent().apply { corrId = "abc-123" }

        producer.publish(event, "utdanning", "vurdering")

        verify { kafkaTemplate.send(any<String>(), eq("abc-123"), any()) }
    }

    @Test
    fun `publish builds the org-scoped request topic from domain and package`() {
        val event = RequestFintEvent().apply { corrId = "abc-123" }

        producer.publish(event, "utdanning", "vurdering")

        verify { kafkaTemplate.send("fintlabs-no.fint-core.event.utdanning-vurdering-request", "abc-123", event) }
    }
}
