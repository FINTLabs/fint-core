package no.fintlabs.client.security.opa

/** What OPA answered. [Unavailable] is kept apart from [Denied] so a caller can be told which one it was. */
sealed interface OpaDecision {
    data class Allowed(
        val fields: Set<String>,
        val relations: Set<String>,
    ) : OpaDecision

    data object Denied : OpaDecision

    data object Unavailable : OpaDecision
}
