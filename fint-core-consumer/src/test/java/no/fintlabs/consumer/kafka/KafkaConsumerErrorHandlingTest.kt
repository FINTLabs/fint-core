package no.fintlabs.consumer.kafka

import io.mockk.mockk
import no.novari.kafka.consuming.ErrorHandlerFactory
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.OffsetResetStrategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.kafka.listener.MessageListenerContainer

@ExtendWith(OutputCaptureExtension::class)
class KafkaConsumerErrorHandlingTest {
    @Test
    fun `should log failed record through error handler`(output: CapturedOutput) {
        val record = ConsumerRecord("test-topic", 2, 42L, "my-key", "my-value")
        val consumer = MockConsumer<String, String>(OffsetResetStrategy.EARLIEST)
        val container = mockk<MessageListenerContainer>()

        val handled =
            ErrorHandlerFactory()
                .createErrorHandler(
                    KafkaConsumerErrorHandling.createLoggingErrorHandlerConfiguration<String>(
                        LoggerFactory.getLogger(KafkaConsumerErrorHandlingTest::class.java),
                        "test-consumer",
                    ),
                ).handleOne(
                    IllegalStateException("boom"),
                    record,
                    consumer,
                    container,
                )

        assertThat(handled).isTrue()
        assertThat(output.out).contains(
            "Kafka consumer test-consumer failed topic=test-topic partition=2 offset=42 key=my-key value=my-value",
            "java.lang.IllegalStateException: boom",
        )
    }
}
