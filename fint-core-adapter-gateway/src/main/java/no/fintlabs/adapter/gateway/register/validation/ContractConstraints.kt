package no.fintlabs.adapter.gateway.register.validation

import no.fintlabs.adapter.models.AdapterCapability
import no.fintlabs.adapter.models.AdapterContract
import no.fintlabs.adapter.models.EventCapability
import org.hibernate.validator.HibernateValidatorConfiguration
import org.hibernate.validator.cfg.GenericConstraintDef
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The rules for a contract that infra-models cannot carry itself, because they need the FINT
 * model. They are added to the validator at start, so `@Valid` on the registration body checks
 * them together with the annotations the library already has.
 */
@Configuration
class ContractConstraints {
    @Bean
    fun contractConstraintMapping(): ValidationConfigurationCustomizer =
        ValidationConfigurationCustomizer { configuration -> applyTo(configuration as HibernateValidatorConfiguration) }

    companion object {
        fun applyTo(configuration: HibernateValidatorConfiguration) {
            val mapping = configuration.createConstraintMapping()
            mapping
                .type(AdapterCapability::class.java)
                .constraint(GenericConstraintDef(KnownResource::class.java))

            mapping
                .type(EventCapability::class.java)
                .constraint(GenericConstraintDef(KnownResource::class.java))

            mapping
                .type(AdapterContract::class.java)
                .field("capabilities")
                .constraint(GenericConstraintDef(ListedOnce::class.java))
                .field("eventCapabilities")
                .constraint(GenericConstraintDef(ListedOnce::class.java))

            configuration.addMapping(mapping)
        }
    }
}
