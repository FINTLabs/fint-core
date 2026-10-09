package no.fintlabs.adapter.gateway.register.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import no.fintlabs.adapter.models.AdapterCapability
import no.fintlabs.adapter.models.EventCapability
import no.novari.fint.core.model.FintModel
import kotlin.reflect.KClass

/**
 * The resource a capability names must exist in the FINT model. The constraint is attached to the
 * infra-models classes by [ContractConstraints], since that library cannot know the model.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [KnownSyncResourceValidator::class, KnownEventResourceValidator::class])
annotation class KnownResource(
    val message: String = "is not a resource in the FINT model",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class KnownSyncResourceValidator : ConstraintValidator<KnownResource, AdapterCapability> {
    override fun isValid(
        capability: AdapterCapability?,
        context: ConstraintValidatorContext,
    ): Boolean = capability == null || context.knows(capability.domainName, capability.packageName, capability.resourceName)
}

class KnownEventResourceValidator : ConstraintValidator<KnownResource, EventCapability> {
    override fun isValid(
        capability: EventCapability?,
        context: ConstraintValidatorContext,
    ): Boolean = capability == null || context.knows(capability.domainName, capability.packageName, capability.resourceName)
}

private fun ConstraintValidatorContext.knows(
    domainName: String?,
    packageName: String?,
    resourceName: String?,
): Boolean {
    if (domainName.isNullOrBlank() || packageName.isNullOrBlank() || resourceName.isNullOrBlank()) return true
    if (FintModel.byPath(domainName, packageName, resourceName) != null) return true

    disableDefaultConstraintViolation()
    buildConstraintViolationWithTemplate("/$domainName/$packageName/$resourceName is not a resource in the FINT model")
        .addConstraintViolation()
    return false
}
