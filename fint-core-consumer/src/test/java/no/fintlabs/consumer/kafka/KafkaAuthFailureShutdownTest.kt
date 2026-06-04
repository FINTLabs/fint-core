package no.fintlabs.consumer.kafka

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.kafka.event.ConsumerStoppedEvent

@ExtendWith(OutputCaptureExtension::class)
class KafkaAuthFailureShutdownTest {
    private val context = mockk<ConfigurableApplicationContext>(relaxed = true)

    @Test
    fun `exits with code 1 when consumer stopped due to auth failure`(output: CapturedOutput) {
        val exitCodes = mutableListOf<Int>()
        val shutdown = KafkaAuthFailureShutdown(context) { exitCodes += it }

        shutdown.onConsumerStopped(
            ConsumerStoppedEvent(this, this, ConsumerStoppedEvent.Reason.AUTH),
        )

        assertThat(exitCodes).containsExactly(1)
        assertThat(output.out).contains("Kafka consumer stopped due to authorization failure")
    }

    @Test
    fun `does not exit on non-auth stop reasons`() {
        val nonAuthReasons =
            ConsumerStoppedEvent.Reason.entries.filter { it != ConsumerStoppedEvent.Reason.AUTH }

        nonAuthReasons.forEach { reason ->
            val exitCodes = mutableListOf<Int>()
            val shutdown = KafkaAuthFailureShutdown(context) { exitCodes += it }

            shutdown.onConsumerStopped(ConsumerStoppedEvent(this, this, reason))

            assertThat(exitCodes).`as`("reason=$reason should not exit").isEmpty()
        }
    }
}
