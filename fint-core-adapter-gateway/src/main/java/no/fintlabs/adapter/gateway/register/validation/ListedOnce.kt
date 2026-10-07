package no.fintlabs.adapter.gateway.register.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import no.fintlabs.adapter.models.AdapterCapability
import no.fintlabs.adapter.models.EventCapability
import kotlin.reflect.KClass

/**
 * A resource may appear only once in a capability list, so a contract has exactly one answer to
 * how it syncs a resource and which operations it covers. Names are compared ignoring case.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [ListedOnceValidator::class])
annotation class ListedOnce(
    val message: String = "lists a resource more than once",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class ListedOnceValidator : ConstraintValidator<ListedOnce, Collection<*>> {
    override fun isValid(
        capabilities: Collection<*>?,
        context: ConstraintValidatorContext,
    ): Boolean {
        val listedTwice =
            capabilities
                .orEmpty()
                .mapNotNull { it.entityUri() }
                .groupingBy { it.lowercase() }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        if (listedTwice.isEmpty()) return true

        context.disableDefaultConstraintViolation()
        context
            .buildConstraintViolationWithTemplate("lists ${listedTwice.joinToString()} more than once")
            .addConstraintViolation()
        return false
    }

    private fun Any?.entityUri(): String? =
        when (this) {
            is AdapterCapability -> entityUri
            is EventCapability -> entityUri
            else -> null
        }
}
