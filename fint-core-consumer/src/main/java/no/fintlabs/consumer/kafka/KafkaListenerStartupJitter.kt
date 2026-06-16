package no.fintlabs.consumer.kafka

import no.fintlabs.consumer.config.ConsumerConfiguration
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationContext
import org.springframework.context.event.EventListener
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.stereotype.Component
import java.util.concurrent.ThreadLocalRandom

@Component
class KafkaListenerStartupJitter(
    private val consumerConfig: ConsumerConfiguration,
    private val applicationContext: ApplicationContext,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(KafkaListenerStartupJitter::class.java)
    }

    @EventListener(ApplicationReadyEvent::class)
    fun startListenersAfterJitter() {
        val jitter = consumerConfig.kafka.startupJitter
        if (jitter.isZero) return

        val containers = applicationContext.getBeansOfType(MessageListenerContainer::class.java).values
        val jitterMs = jitter.toMillis()

        logger.info(
            "Scheduling {} Kafka listener containers with independent startup jitter (max {} ms)",
            containers.size,
            jitterMs,
        )

        containers.forEach { container ->
            val delayMs = ThreadLocalRandom.current().nextLong(jitterMs + 1)
            val listenerId = container.listenerId ?: container.toString()
            Thread.ofVirtual().name("kafka-startup-jitter-$listenerId").start {
                try {
                    Thread.sleep(delayMs)
                } catch (ex: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logger.warn("Kafka startup jitter interrupted for {}; starting immediately", listenerId)
                }
                if (!container.isRunning) {
                    logger.info("Starting Kafka listener container {} after {} ms jitter", listenerId, delayMs)
                    container.start()
                }
            }
        }
    }
}
