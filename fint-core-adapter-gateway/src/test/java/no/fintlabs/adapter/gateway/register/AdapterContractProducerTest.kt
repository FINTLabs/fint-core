package no.fintlabs.adapter.gateway.register

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.adapter.gateway.kafka.EventPublisher
import no.fintlabs.adapter.gateway.kafka.topic.TopicNamesConstants.ADAPTER_CONTRACT
import no.fintlabs.adapter.models.AdapterContract
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class AdapterContractProducerTest {
    private val eventPublisher: EventPublisher = mockk()
    private val producer = AdapterContractProducer(eventPublisher)

    init {
        every { eventPublisher.publish(any(), any(), any()) } returns CompletableFuture()
    }

    @Test
    fun `a contract is keyed on username and the normalised orgId`() {
        val contract =
            AdapterContract
                .builder()
                .username("adapter@afk.no")
                .orgId("AFK-no")
                .adapterId("a")
                .build()

        producer.send(contract)

        verify { eventPublisher.publish(ADAPTER_CONTRACT, "adapter@afk.no\u001Fafk.no", contract) }
    }

    @Test
    fun `a tombstone has the same key and a null value`() {
        producer.sendTombstone("adapter@afk.no", "afk_no")

        verify { eventPublisher.publish(ADAPTER_CONTRACT, "adapter@afk.no\u001Fafk.no", null) }
    }

    @Test
    fun `the key separator matches the buffer topic convention`() {
        assertThat(AdapterContractProducer.keyOf("u", "org.no")).isEqualTo("u\u001Forg.no")
    }
}
