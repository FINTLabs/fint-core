package no.fintlabs.adapter.gateway.register

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import no.fintlabs.adapter.models.AdapterCapability
import no.fintlabs.adapter.models.AdapterContract
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
                .isEqualTo(ContractLookup.Found(setOf(CapabilityKey("utdanning", "elev", "elev"))))
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
                .isEqualTo(ContractLookup.Found(setOf(CapabilityKey("utdanning", "elev", "elev"))))
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

    @Test
    fun `returns the distinct adapter ids the store knows about`() {
        val ids = setOf("adapter-1", "adapter-2")
        every { contractJpaRepository.getAdapterIds() } returns ids

        assertThat(contractService.getAdapterIds()).isEqualTo(ids)
    }

    private fun givenStoredContract(vararg capabilities: AdapterCapability) {
        every {
            contractJpaRepository.findByUserNameAndOrgId(any(), any())
        } returns ContractEntity(contract(capabilities = capabilities.toSet()))
    }

    private fun contract(
        orgId: String = ORG_ID,
        capabilities: Set<AdapterCapability> = setOf(capability("utdanning", "elev", "elev")),
    ): AdapterContract =
        AdapterContract().apply {
            this.adapterId = "adapter-1"
            this.orgId = orgId
            this.username = USERNAME
            this.heartbeatIntervalInMinutes = 5
            this.capabilities = capabilities
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
