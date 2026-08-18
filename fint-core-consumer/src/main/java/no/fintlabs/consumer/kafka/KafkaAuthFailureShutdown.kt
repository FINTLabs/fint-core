package no.fintlabs.consumer.kafka

import org.slf4j.LoggerFactory
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.event.EventListener
import org.springframework.kafka.event.ConsumerStoppedEvent
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

@Component
class KafkaAuthFailureShutdown(
    private val context: ConfigurableApplicationContext,
    private val exit: (Int) -> Unit = { exitProcess(it) },
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun onConsumerStopped(event: ConsumerStoppedEvent) {
        if (event.reason != ConsumerStoppedEvent.Reason.AUTH) return
        log.error("Kafka consumer stopped due to authorization failure - exiting for restart")
        exit(SpringApplication.exit(context, { 1 }))
    }
}
