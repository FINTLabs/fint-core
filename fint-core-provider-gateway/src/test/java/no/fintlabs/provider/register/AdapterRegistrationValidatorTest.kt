package no.fintlabs.provider.register

import no.fintlabs.adapter.models.AdapterCapability
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class AdapterRegistrationValidatorTest {
    private val sut = AdapterRegistrationValidator()

    private val domainName = "utdanning"
    private val packageName = "vurdering"
    private val resourceName = "elevfravar"

    @Test
    fun `empty list of capabilities are valid`() {
        assertDoesNotThrow { sut.validateCapabilities(listOf()) }
    }

    @Test
    fun `a resource the model declares is valid`() {
        val capability = createCapability(domainName, packageName, resourceName)

        assertDoesNotThrow { sut.validateCapabilities(listOf(capability)) }
    }

    @Test
    fun `a resource the model does not declare is invalid`() {
        val capability = createCapability(domainName, packageName, "finnesikke")

        assertThrows<InvalidAdapterCapabilityException> { sut.validateCapabilities(listOf(capability)) }
    }

    @Test
    fun `a resource in the wrong component is invalid`() {
        val capability = createCapability(domainName, "elev", resourceName)

        assertThrows<InvalidAdapterCapabilityException> { sut.validateCapabilities(listOf(capability)) }
    }

    @Test
    fun `resource lookup ignores case`() {
        val capability = createCapability("Utdanning", "Vurdering", "Elevfravar")

        assertDoesNotThrow { sut.validateCapabilities(listOf(capability)) }
    }

    @Test
    fun `fullSyncIntervalInDays between 1 and 7 is valid`() {
        val capabilities = (1..7).map { createCapability(fullSyncIntervalInDays = it) }

        assertDoesNotThrow { sut.validateCapabilities(capabilities) }
    }

    @Test
    fun `fullSyncIntervalInDays under 1 is invalid`() {
        val capability = createCapability(fullSyncIntervalInDays = 0)

        assertThrows<InvalidAdapterCapabilityException> { sut.validateCapabilities(listOf(capability)) }
    }

    @Test
    fun `fullSyncIntervalInDays above 7 days is invalid`() {
        val capability = createCapability(fullSyncIntervalInDays = 8)

        assertThrows<InvalidAdapterCapabilityException> { sut.validateCapabilities(listOf(capability)) }
    }

    private fun createCapability(
        domainName: String = this.domainName,
        packageName: String = this.packageName,
        resourceName: String = this.resourceName,
        fullSyncIntervalInDays: Int = 1,
    ) = AdapterCapability().apply {
        this.domainName = domainName
        this.packageName = packageName
        this.resourceName = resourceName
        this.fullSyncIntervalInDays = fullSyncIntervalInDays
    }
}
