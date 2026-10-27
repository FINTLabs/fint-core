package no.fintlabs.adapter.gateway

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

/**
 * Starts a single [ConfluentKafkaContainer] per JVM and wires Spring's bootstrap-servers to it.
 */
abstract class KafkaContainerBaseIT {
    companion object {
        private const val IMAGE = "confluentinc/cp-kafka:7.9.2"

        @JvmStatic
        val KAFKA: ConfluentKafkaContainer =
            ConfluentKafkaContainer(DockerImageName.parse(IMAGE)).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun kafkaProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers") { KAFKA.bootstrapServers }
            registry.add("fint.provider.kafka.replicas") { "1" }
        }
    }
}
