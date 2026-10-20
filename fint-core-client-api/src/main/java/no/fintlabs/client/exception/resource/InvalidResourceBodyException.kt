package no.fintlabs.client.exception.resource

class InvalidResourceBodyException(
    resourceName: String,
    cause: Throwable,
) : RuntimeException("The body is not a valid $resourceName: ${cause.message}", cause)
