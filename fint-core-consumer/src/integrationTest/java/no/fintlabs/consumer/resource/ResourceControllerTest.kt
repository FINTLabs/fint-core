package no.fintlabs.consumer.resource

import no.fintlabs.model.resource.FintResources
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "fint.security.enabled=false",
    ],
)
@EmbeddedKafka
@ActiveProfiles("utdanning-elev")
class ResourceControllerTest {
    @LocalServerPort
    private var port: Int = 0

    private val client by lazy {
        WebTestClient.bindToServer().baseUrl("http://localhost:$port/utdanning/elev").build()
    }

    private val resource = "elev"
    private val corrId = "abc-123"

    @Test
    fun `GET resource returns 200`() {
        client
            .get()
            .uri("/$resource")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<FintResources>()
    }

    @Test
    fun `GET unknown resource returns 404`() {
        client
            .get()
            .uri("/unknownresource")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `GET resource by valid id field returns 404 when value not found`() {
        client
            .get()
            .uri("/$resource/systemid/nonexistent")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `GET resource by invalid id field returns 404`() {
        client
            .get()
            .uri("/$resource/invalidfield/123")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `GET last-updated returns 200 with timestamp`() {
        client
            .get()
            .uri("/$resource/last-updated")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.lastUpdated")
            .exists()
    }

    @Test
    fun `GET cache-size returns 200`() {
        client
            .get()
            .uri("/$resource/cache/size")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.size")
            .exists()
    }

    @Test
    fun `GET status returns 410 for unknown corrId`() {
        client
            .get()
            .uri("/$resource/status/$corrId")
            .exchange()
            .expectStatus()
            .isEqualTo(410)
    }

    @Test
    fun `POST resource returns 202 with location header`() {
        client
            .post()
            .uri("/$resource")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("name" to "Test Elev"))
            .exchange()
            .expectStatus()
            .isAccepted
            .expectHeader()
            .exists("Location")
    }

    @Test
    fun `PUT resource returns 202 with location header`() {
        client
            .put()
            .uri("/$resource/systemid/123")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("name" to "Updated Elev"))
            .exchange()
            .expectStatus()
            .isAccepted
            .expectHeader()
            .exists("Location")
    }

    @Test
    fun `PUT resource with invalid id field returns 404`() {
        client
            .put()
            .uri("/$resource/invalidfield/123")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("name" to "Updated Elev"))
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `POST query returns 200`() {
        client
            .post()
            .uri("/$resource/\$query")
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue("systemId/identifikatorverdi eq '123'")
            .exchange()
            .expectStatus()
            .isOk
    }
}
