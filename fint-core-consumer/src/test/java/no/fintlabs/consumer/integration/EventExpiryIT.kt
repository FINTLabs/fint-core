package no.fintlabs.consumer.integration

import no.fintlabs.Application
import no.fintlabs.config.KafkaTestConfig
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult
import java.time.Duration

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [Application::class])
@EmbeddedKafka(partitions = 1)
@TestPropertySource(
    properties = [
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "novari.kafka.default-replicas=1",
        "fint.relation.base-url=https://test.felleskomponent.no",
        "fint.org-id=foo.org",
        "fint.consumer.org-id=foo.org",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package=elev",
        "fint.security.enabled=false",
        "fint.consumer.event.defaults.eviction=1s",
    ],
)
@Import(KafkaTestConfig::class)
@DirtiesContext
class EventExpiryIT {
    @LocalServerPort
    private var port: Int = 0

    private val client by lazy {
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port/utdanning/elev")
            .responseTimeout(Duration.ofSeconds(10))
            .build()
    }

    private val resourceName = "elev"

    @Test
    fun `corrId expires before adapter responds - status endpoint returns 410`() {
        val location =
            client
                .post()
                .uri("/$resourceName")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("systemId" to mapOf("identifikatorverdi" to "123")))
                .exchange()
                .expectStatus()
                .isAccepted
                .returnResult<Void>()
                .responseHeaders
                .location
                ?.toString()
                ?: error("No Location header in response")

        val corrId = location.substringAfterLast("/")

        // Wait for the request to be tracked (202), then for the eviction to kick in (410)
        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            client
                .get()
                .uri("/$resourceName/status/$corrId")
                .exchange()
                .expectStatus()
                .isEqualTo(410)
        }
    }
}
