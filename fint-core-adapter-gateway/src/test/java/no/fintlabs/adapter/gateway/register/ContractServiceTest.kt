package no.fintlabs.adapter.gateway.register

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import no.fintlabs.adapter.models.AdapterCapability
import no.fintlabs.adapter.models.AdapterContract
import no.fintlabs.adapter.models.EventCapability
import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.resourceRefOf
import no.novari.fint.core.model.FintResourceRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ContractServiceTest {
    private val contractJpaRepository = mockk<ContractJpaRepository>()
    private val contractService = ContractService(contractJpaRepository)

    @BeforeEach
    fun saveReturnsTheEntityItWasGiven() {
        every { contractJpaRepository.save(any<ContractEntity>()) } answers { firstArg() }
    }

    @Nested
    inner class Lookup {
        @Test
        fun `reports the capabilities of a stored contract`() {
            givenStoredContract(capability("utdanning", "elev", "elev"))

            val lookup = contractService.lookup(USERNAME, ORG_ID)

            assertThat(lookup)
                .isEqualTo(found(syncResources = setOf(resourceRefOf("utdanning", "elev", "elev"))))
        }

        @Test
        fun `reports the event capabilities of a stored contract`() {
            givenStoredContract(
                contract(
                    eventCapabilities =
                        setOf(
                            eventCapability("utdanning", "vurdering", "elevfravar", OperationType.READ),
                            eventCapability("utdanning", "vurdering", "fravar", OperationType.CREATE, OperationType.UPDATE),
                        ),
                ),
            )

            val lookup = contractService.lookup(USERNAME, ORG_ID) as ContractLookup.Found

            assertThat(lookup.contract.eventCapabilities).isEqualTo(
                EventCapabilities(
                    mapOf(
                        resourceRefOf("utdanning", "vurdering", "elevfravar") to setOf(OperationType.READ),
                        resourceRefOf("utdanning", "vurdering", "fravar") to setOf(OperationType.CREATE, OperationType.UPDATE),
                    ),
                ),
            )
        }

        @Test
        fun `reports absent when the adapter has no contract for the org`() {
            every { contractJpaRepository.findByUserNameAndOrgId(any(), any()) } returns null

            assertThat(contractService.lookup(USERNAME, ORG_ID)).isEqualTo(ContractLookup.Absent)
        }

        @Test
        fun `looks up the same contract whichever separator the caller uses`() {
            givenStoredContract(capability("utdanning", "elev", "elev"))

            contractService.lookup(USERNAME, "test-org-no")

            verify { contractJpaRepository.findByUserNameAndOrgId(USERNAME, ORG_ID) }
        }

        @Test
        fun `matches capabilities regardless of the case the adapter registered them in`() {
            givenStoredContract(capability("Utdanning", "Elev", "Elev"))

            val lookup = contractService.lookup(USERNAME, ORG_ID)

            assertThat(lookup)
                .isEqualTo(found(syncResources = setOf(resourceRefOf("utdanning", "elev", "elev"))))
        }
    }

    @Nested
    inner class SaveContract {
        @Test
        fun `stores a new contract for a username and org pair that has none`() {
            every { contractJpaRepository.findByUserNameAndOrgId(any(), any()) } returns null
            val saved = slot<ContractEntity>()
            every { contractJpaRepository.save(capture(saved)) } answers { saved.captured }

            contractService.saveContract(contract())

            assertThat(saved.captured.userName).isEqualTo(USERNAME)
            assertThat(saved.captured.orgId).isEqualTo(ORG_ID)
            assertThat(saved.captured.capabilityEntityset.map { it.resourceName }).containsExactly("elev")
        }

        @Test
        fun `updates the existing row rather than adding a second one for the same org`() {
            val existing = ContractEntity(contract())
            every { contractJpaRepository.findByUserNameAndOrgId(any(), any()) } returns existing
            val saved = slot<ContractEntity>()
            every { contractJpaRepository.save(capture(saved)) } answers { saved.captured }

            contractService.saveContract(contract(capabilities = setOf(capability("utdanning", "elev", "skoleressurs"))))

            assertThat(saved.captured).isSameAs(existing)
            assertThat(saved.captured.capabilityEntityset.map { it.resourceName }).containsExactly("skoleressurs")
        }

        @Test
        fun `keeps a second org's contract separate from the first for the same adapter`() {
            every { contractJpaRepository.findByUserNameAndOrgId(USERNAME, ORG_ID) } returns
                ContractEntity(contract(orgId = ORG_ID))
            every { contractJpaRepository.findByUserNameAndOrgId(USERNAME, OTHER_ORG_ID) } returns null
            val saved = slot<ContractEntity>()
            every { contractJpaRepository.save(capture(saved)) } answers { saved.captured }

            contractService.saveContract(
                contract(orgId = OTHER_ORG_ID, capabilities = setOf(capability("administrasjon", "personal", "person"))),
            )

            assertThat(saved.captured.orgId).isEqualTo(OTHER_ORG_ID)
            verify(exactly = 0) { contractJpaRepository.save(match { it.orgId == ORG_ID }) }
        }

        @Test
        fun `normalizes the org so one contract cannot be stored twice under two spellings`() {
            every { contractJpaRepository.findByUserNameAndOrgId(any(), any()) } returns null
            val saved = slot<ContractEntity>()
            every { contractJpaRepository.save(capture(saved)) } answers { saved.captured }

            contractService.saveContract(contract(orgId = "test-org-no"))

            assertThat(saved.captured.orgId).isEqualTo(ORG_ID)
        }
    }

    private fun givenStoredContract(vararg capabilities: AdapterCapability) =
        givenStoredContract(contract(capabilities = capabilities.toSet()))

    private fun givenStoredContract(contract: AdapterContract) {
        every {
            contractJpaRepository.findByUserNameAndOrgId(any(), any())
        } returns ContractEntity(contract)
    }

    private fun found(
        syncResources: Set<FintResourceRef>,
        eventCapabilities: EventCapabilities = EventCapabilities.NONE,
    ): ContractLookup.Found = ContractLookup.Found(RegisteredContract(OrgId.from(ORG_ID), syncResources, eventCapabilities))

    private fun contract(
        orgId: String = ORG_ID,
        capabilities: Set<AdapterCapability> = setOf(capability("utdanning", "elev", "elev")),
        eventCapabilities: Set<EventCapability> = emptySet(),
    ): AdapterContract =
        AdapterContract().apply {
            this.adapterId = "adapter-1"
            this.orgId = orgId
            this.username = USERNAME
            this.heartbeatIntervalInMinutes = 5
            this.capabilities = capabilities
            this.eventCapabilities = eventCapabilities
        }

    private fun eventCapability(
        domain: String,
        pkg: String,
        resource: String,
        vararg operations: OperationType,
    ): EventCapability =
        EventCapability().apply {
            this.domainName = domain
            this.packageName = pkg
            this.resourceName = resource
            this.operations = operations.toSet()
        }

    private fun capability(
        domain: String,
        pkg: String,
        resource: String,
    ): AdapterCapability =
        AdapterCapability().apply {
            this.domainName = domain
            this.packageName = pkg
            this.resourceName = resource
            this.fullSyncIntervalInDays = 1
            this.deltaSyncInterval = AdapterCapability.DeltaSyncInterval.IMMEDIATE
        }

    private companion object {
        const val ORG_ID = "test.org.no"
        const val OTHER_ORG_ID = "sub.test.org.no"
        const val USERNAME = "test@adapter.test.org.no"
    }
}
