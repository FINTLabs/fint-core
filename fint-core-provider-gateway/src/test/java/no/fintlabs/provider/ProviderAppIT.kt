package no.fintlabs.provider

import no.fintlabs.provider.sync.BufferReader
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean

/**
 * Shared context for ITs that boot the full application against the per-JVM Kafka container.
 * Subclasses must not add anything that changes the merged context configuration: the buffer
 * listener's consumer group has a single partition, so two live contexts would race for it and
 * records would land in the wrong test's Mongo. The spy lives here for the same reason; it is
 * part of the context key whether or not a subclass uses it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [Application::class])
@Import(TestcontainersConfiguration::class)
abstract class ProviderAppIT : KafkaContainerBaseIT() {
    @MockitoSpyBean
    protected lateinit var bufferReader: BufferReader
}
