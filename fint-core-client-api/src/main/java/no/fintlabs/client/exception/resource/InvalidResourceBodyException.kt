package no.fintlabs.client.exception.resource

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

// no handler: Spring Boot's error controller renders the same body as for a body that is not JSON
@ResponseStatus(HttpStatus.BAD_REQUEST)
class InvalidResourceBodyException(
    resourceName: String,
    cause: Throwable,
) : RuntimeException("The body is not a valid $resourceName: ${cause.message}", cause)
