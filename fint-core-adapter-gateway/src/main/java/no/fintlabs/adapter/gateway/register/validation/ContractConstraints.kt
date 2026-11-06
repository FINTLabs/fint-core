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
 * model, and the infra-models rules the gateway lifts so it accepts what provider-gateway
 * accepted. They are added to the validator at start, so `@Valid` on the registration body
 * checks them together with the annotations the library already has.
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
                // infra-models marks it @NotNull, but adapters may leave it out: the gateway
                // stores a missing value as "Not Present" and the republisher reads it back as null.
                .field("deltaSyncInterval")
                .ignoreAnnotations(true)

            mapping
                .type(EventCapability::class.java)
                .constraint(GenericConstraintDef(KnownResource::class.java))

            mapping
                .type(AdapterContract::class.java)
                // provider-gateway took any adapter id and heartbeat interval, and status-service
                // shows both. RegistrationService puts the username in for a missing adapter id.
                .field("adapterId")
                .ignoreAnnotations(true)
                .field("heartbeatIntervalInMinutes")
                .ignoreAnnotations(true)
                // A sync resource listed twice is merged by RegistrationService instead.
                .field("eventCapabilities")
                .constraint(GenericConstraintDef(ListedOnce::class.java))

            configuration.addMapping(mapping)
        }
    }
}
