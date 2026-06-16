package no.fintlabs.autorelation.kafka

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.autorelation.model.EntityDescriptor
import no.fintlabs.autorelation.model.RelationBinding
import no.fintlabs.autorelation.model.RelationOperation
import no.fintlabs.autorelation.model.RelationUpdate
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.config.OrgId
import no.fintlabs.consumer.kafka.KafkaThroughputMetrics
import no.novari.fint.model.resource.Link
import no.novari.kafka.producing.ParameterizedProducerRecord
import no.novari.kafka.producing.ParameterizedTemplate
import no.novari.kafka.producing.ParameterizedTemplateFactory
import org.junit.jupiter.api.Test
import org.springframework.kafka.support.SendResult
import java.util.concurrent.CompletableFuture

class RelationUpdateProducerTest {
    private val factory: ParameterizedTemplateFactory = mockk()
    private val template: ParameterizedTemplate<RelationUpdate> = mockk()
    private val consumerConfiguration: ConsumerConfiguration = mockk(relaxed = true)
    private val kafkaThroughputMetrics: KafkaThroughputMetrics = mockk(relaxed = true)

    private val update =
        RelationUpdate(
            targetEntity = EntityDescriptor("utdanning", "vurdering", "elevfravar"),
            targetIds = listOf("123"),
            binding = RelationBinding(relationName = "fravarsregistrering", link = Link.with("systemid/abc")),
            operation = RelationOperation.ADD,
        )

    private fun newProducer(future: CompletableFuture<SendResult<String, RelationUpdate>>): RelationUpdateProducer {
        every { factory.createTemplate(RelationUpdate::class.java) } returns template
        every { template.send(any<ParameterizedProducerRecord<RelationUpdate>>()) } returns future
        every { consumerConfiguration.orgId } returns OrgId.from("fintlabs.no")
        return RelationUpdateProducer(factory, consumerConfiguration, kafkaThroughputMetrics)
    }

    @Test
    fun `records published when send completes successfully`() {
        val sendResult: SendResult<String, RelationUpdate> = mockk(relaxed = true)
        val producer = newProducer(CompletableFuture.completedFuture(sendResult))

        producer.publishRelationUpdate(update, "elev", "elev-1").join()

        verify(exactly = 1) {
            kafkaThroughputMetrics.recordRelationUpdateProduced("elevfravar", "ADD", "published")
        }
        verify(exactly = 0) {
            kafkaThroughputMetrics.recordRelationUpdateProduced(any(), any(), "failed")
        }
    }

    @Test
    fun `records failed when send completes exceptionally`() {
        val failed: CompletableFuture<SendResult<String, RelationUpdate>> = CompletableFuture()
        failed.completeExceptionally(RuntimeException("broker unavailable"))
        val producer = newProducer(failed)

        runCatching { producer.publishRelationUpdate(update, "elev", "elev-1").join() }

        verify(exactly = 1) {
            kafkaThroughputMetrics.recordRelationUpdateProduced("elevfravar", "ADD", "failed")
        }
        verify(exactly = 0) {
            kafkaThroughputMetrics.recordRelationUpdateProduced(any(), any(), "published")
        }
    }
}
