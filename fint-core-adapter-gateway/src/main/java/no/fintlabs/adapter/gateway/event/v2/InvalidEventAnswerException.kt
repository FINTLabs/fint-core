package no.fintlabs.adapter.gateway.event.v2

/**
 * A v2 answer that does not fit the request it answers, for example too many resources. It is
 * answered with a 400 ProblemDetail that tells the adapter what to send instead.
 */
class InvalidEventAnswerException(
    message: String,
) : RuntimeException(message)
