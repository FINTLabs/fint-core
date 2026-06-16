package no.fintlabs.consumer.exception

import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.exception.kafka.ConsumerErrorPublisher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.ResponseEntity
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@ExtendWith(MockitoExtension::class)
class GlobalExceptionHandlerTest {
    @Mock
    private lateinit var consumerErrorPublisher: ConsumerErrorPublisher

    private lateinit var webTestClient: WebTestClient

    @BeforeEach
    fun setUp() {
        val configuration =
            ConsumerConfiguration(
                baseUrl = "https://test.felleskomponent.no",
                orgIdValue = "fintlabs.no",
                domain = "utdanning",
                packageName = "vurdering",
                podUrl = "http://test",
            )
        webTestClient =
            WebTestClient
                .bindToController(NotFoundController(), ErrorController())
                .controllerAdvice(GlobalExceptionHandler(consumerErrorPublisher, configuration))
                .build()
    }

    @Test
    @DisplayName("Should return 404 when id is not found")
    fun shouldReturn404OnNotFoundId() {
        // No route matches /{resource}/{id} (only 2 segments) — WebFlux returns 404 at routing level
        webTestClient
            .get()
            .uri("/elevfravar/1234")
            .exchange()
            .expectStatus()
            .isNotFound()

        verifyNoInteractions(consumerErrorPublisher)
    }

    @Test
    @DisplayName("Should return 404 when resource is not found")
    fun shouldReturn404WhenResourceIsNotFound() {
        // Route matches but handler returns notFound() — no exception raised
        webTestClient
            .get()
            .uri("/elevfravar/systemid/1234")
            .exchange()
            .expectStatus()
            .isNotFound()

        verifyNoInteractions(consumerErrorPublisher)
    }

    @Test
    @DisplayName("Should return 500 when we get a RuntimeException")
    fun shouldReturn500WhenWeGetARuntimeException() {
        webTestClient
            .get()
            .uri("/bob")
            .exchange()
            .expectStatus()
            .is5xxServerError()

        verify(consumerErrorPublisher).publish(anyNonNull())
    }

    // Mockito.any() returns null but Kotlin inserts null checks for non-null parameters.
    // Removing the `: Any` bound makes this an unchecked cast — Kotlin skips the runtime check.
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T = Mockito.any<T?>() as T

    @RestController
    class NotFoundController {
        @GetMapping("/{resource}/{idField}/{idValue}")
        fun getById(
            @PathVariable resource: String,
            @PathVariable idField: String,
            @PathVariable idValue: String,
        ): ResponseEntity<Any> = ResponseEntity.notFound().build()
    }

    @RestController
    class ErrorController {
        @GetMapping("/bob")
        fun triggerError(): Mono<String> = Mono.error(RuntimeException("Something went wrong!"))
    }
}
