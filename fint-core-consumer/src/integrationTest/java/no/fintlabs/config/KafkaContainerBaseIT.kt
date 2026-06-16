package no.fintlabs.config

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

/**
 * Starts a single [ConfluentKafkaContainer] per JVM, wires Spring's bootstrap-servers to it, and
 * turns on `fint.consumer.kafka.bootstrap-topics` so `TopicBootstrapper` pre-creates the topics
 * a consumer configured via `fint.consumer.{org-id,domain,package}` needs.
 *
 * Subclasses supply those three properties (usually via `@TestPropertySource`).
 */
abstract class KafkaContainerBaseIT {
    companion object {
        private const val IMAGE = "confluentinc/cp-kafka:7.8.0"

        @JvmStatic
        val KAFKA: ConfluentKafkaContainer =
            ConfluentKafkaContainer(DockerImageName.parse(IMAGE)).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun kafkaProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers") { KAFKA.bootstrapServers }
            registry.add("novari.kafka.default-replicas") { "1" }
            registry.add("fint.consumer.kafka.bootstrap-topics") { "true" }
        }
    }
}
