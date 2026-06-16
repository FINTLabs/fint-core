package no.fintlabs.consumer.exception

import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.exception.kafka.ConsumerErrorPublisher
import no.fintlabs.status.models.error.ConsumerError
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler

@ControllerAdvice
class GlobalExceptionHandler(
    private val consumerErrorPublisher: ConsumerErrorPublisher,
    private val configuration: ConsumerConfiguration,
) : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(Exception::class)
    fun handleExceptions(ex: Exception): ResponseEntity<*> {
        log.error("Caught in global exception handler:", ex)
        consumerErrorPublisher.publish(
            ConsumerError.fromException(
                ex,
                configuration.domain,
                configuration.packageName,
                configuration.orgId.value,
            ),
        )
        return ResponseEntity.internalServerError().build<Any>()
    }
}
