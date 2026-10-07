package no.fintlabs.adapter.gateway.register.validation

import jakarta.validation.Validation
import jakarta.validation.Validator
import no.fintlabs.adapter.models.AdapterCapability
import no.fintlabs.adapter.models.AdapterContract
import no.fintlabs.adapter.models.EventCapability
import no.fintlabs.adapter.operation.OperationType
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.validator.HibernateValidatorConfiguration
import org.junit.jupiter.api.Test

/**
 * Validates contracts the way the registration endpoint does: the annotations from infra-models
 * together with the rules [ContractConstraints] adds.
 */
class ContractConstraintsTest {
    private val validator: Validator =
        (Validation.byDefaultProvider().configure() as HibernateValidatorConfiguration)
            .also(ContractConstraints::applyTo)
            .buildValidatorFactory()
            .validator

    @Test
    fun `a contract with known resources and sound values is valid`() {
        val contract =
            contract(
                capabilities = setOf(capability("elev", "elev")),
                eventCapabilities = setOf(eventCapability("vurdering", "elevfravar", OperationType.READ)),
            )

        assertThat(violations(contract)).isEmpty()
    }

    @Test
    fun `a sync capability for a resource the model does not know is refused`() {
        val contract = contract(capabilities = setOf(capability("vurdering", "finnesikke")))

        assertThat(violations(contract))
            .containsExactly("capabilities[]" to "/utdanning/vurdering/finnesikke is not a resource in the FINT model")
    }

    @Test
    fun `an event capability for a resource the model does not know is refused`() {
        val contract = contract(eventCapabilities = setOf(eventCapability("vurdering", "finnesikke", OperationType.READ)))

        assertThat(violations(contract))
            .containsExactly("eventCapabilities[]" to "/utdanning/vurdering/finnesikke is not a resource in the FINT model")
    }

    @Test
    fun `a resource lookup ignores case`() {
        val contract = contract(capabilities = setOf(capability("Vurdering", "Elevfravar")))

        assertThat(violations(contract)).isEmpty()
    }

    @Test
    fun `a full sync interval outside one to seven days is refused`() {
        assertThat(violations(contract(capabilities = setOf(capability("elev", "elev", fullSyncIntervalInDays = 0)))))
            .extracting<String> { it.first }
            .containsExactly("capabilities[].fullSyncIntervalInDays")
        assertThat(violations(contract(capabilities = setOf(capability("elev", "elev", fullSyncIntervalInDays = 8)))))
            .extracting<String> { it.first }
            .containsExactly("capabilities[].fullSyncIntervalInDays")
    }

    @Test
    fun `an event capability without operations is refused`() {
        val contract = contract(eventCapabilities = setOf(eventCapability("vurdering", "elevfravar")))

        assertThat(violations(contract))
            .extracting<String> { it.first }
            .containsExactly("eventCapabilities[].operations")
    }

    @Test
    fun `a resource listed twice among the event capabilities is refused`() {
        val contract =
            contract(
                eventCapabilities =
                    setOf(
                        eventCapability("vurdering", "elevfravar", OperationType.READ),
                        eventCapability("vurdering", "ELEVFRAVAR", OperationType.CREATE),
                    ),
            )

        assertThat(violations(contract))
            .containsExactly("eventCapabilities" to "lists /utdanning/vurdering/elevfravar more than once")
    }

    @Test
    fun `a resource listed twice among the sync capabilities is refused`() {
        val contract =
            contract(
                capabilities =
                    setOf(
                        capability("elev", "elev", fullSyncIntervalInDays = 1),
                        capability("elev", "Elev", fullSyncIntervalInDays = 7),
                    ),
            )

        assertThat(violations(contract))
            .containsExactly("capabilities" to "lists /utdanning/elev/elev more than once")
    }

    @Test
    fun `a heartbeat outside one to five minutes is refused`() {
        assertThat(violations(contract(heartbeat = 0)))
            .extracting<String> { it.first }
            .containsExactly("heartbeatIntervalInMinutes")
    }

    private fun violations(contract: AdapterContract): List<Pair<String, String>> =
        validator
            .validate(contract)
            .map { it.propertyPath.toString() to it.message }
            .sortedBy { it.first }

    private fun contract(
        heartbeat: Int = 5,
        capabilities: Set<AdapterCapability> = emptySet(),
        eventCapabilities: Set<EventCapability> = emptySet(),
    ): AdapterContract =
        AdapterContract().apply {
            this.adapterId = "https://test.com/fintlabs.no/utdanning"
            this.orgId = "fintlabs.no"
            this.username = "test@adapter.fintlabs.no"
            this.heartbeatIntervalInMinutes = heartbeat
            this.capabilities = capabilities
            this.eventCapabilities = eventCapabilities
        }

    private fun capability(
        pkg: String,
        resource: String,
        fullSyncIntervalInDays: Int = 1,
    ): AdapterCapability =
        AdapterCapability().apply {
            this.domainName = "utdanning"
            this.packageName = pkg
            this.resourceName = resource
            this.fullSyncIntervalInDays = fullSyncIntervalInDays
            this.deltaSyncInterval = AdapterCapability.DeltaSyncInterval.IMMEDIATE
        }

    private fun eventCapability(
        pkg: String,
        resource: String,
        vararg operations: OperationType,
    ): EventCapability =
        EventCapability().apply {
            this.domainName = "utdanning"
            this.packageName = pkg
            this.resourceName = resource
            this.operations = operations.toSet()
        }
}
