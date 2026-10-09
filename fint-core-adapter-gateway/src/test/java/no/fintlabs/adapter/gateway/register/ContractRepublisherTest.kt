package no.fintlabs.adapter.gateway.register

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import no.fintlabs.adapter.models.AdapterCapability
import no.fintlabs.adapter.models.AdapterContract
import no.fintlabs.adapter.operation.OperationType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ContractRepublisherTest {
    private val repository: ContractJpaRepository = mockk()
    private val producer: AdapterContractProducer = mockk()
    private val clock = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC)
    private val republisher = ContractRepublisher(repository, producer, clock)

    @Test
    fun `every stored contract is published with its capabilities`() {
        every { repository.findAllWithCapabilities() } returns listOf(contract("Not Present"), contract("IMMEDIATE"))
        val published = mutableListOf<AdapterContract>()
        every { producer.send(capture(published)) } just runs

        republisher.republishContracts()

        verify(exactly = 2) { producer.send(any()) }
        with(published.first()) {
            assertThat(username).isEqualTo("adapter@afk.no")
            assertThat(orgId).isEqualTo("afk.no")
            assertThat(adapterId).isEqualTo("adapter-1")
            assertThat(heartbeatIntervalInMinutes).isEqualTo(1)
            assertThat(time).isEqualTo(clock.millis())
            assertThat(capabilities.single().entityUri).isEqualTo("/utdanning/elev/elev")
            assertThat(capabilities.single().deltaSyncInterval).isNull()
        }
        assertThat(
            published
                .last()
                .capabilities
                .single()
                .deltaSyncInterval,
        ).isEqualTo(AdapterCapability.DeltaSyncInterval.IMMEDIATE)
    }

    @Test
    fun `a stored contract is published with its event capabilities, one entry per resource`() {
        val stored = contract("IMMEDIATE")
        stored.eventCapabilityEntityset.add(eventCapability(stored, "elevfravar", OperationType.READ))
        stored.eventCapabilityEntityset.add(eventCapability(stored, "elevfravar", OperationType.CREATE))
        stored.eventCapabilityEntityset.add(eventCapability(stored, "fravar", OperationType.READ))
        every { repository.findAllWithCapabilities() } returns listOf(stored)
        val published = slot<AdapterContract>()
        every { producer.send(capture(published)) } just runs

        republisher.republishContracts()

        val byResource = published.captured.eventCapabilities.associateBy { it.entityUri }
        assertThat(byResource.keys).containsExactlyInAnyOrder("/utdanning/vurdering/elevfravar", "/utdanning/vurdering/fravar")
        assertThat(byResource.getValue("/utdanning/vurdering/elevfravar").operations)
            .containsExactlyInAnyOrder(OperationType.READ, OperationType.CREATE)
        assertThat(byResource.getValue("/utdanning/vurdering/fravar").operations).containsExactly(OperationType.READ)
    }

    @Test
    fun `a stored contract without event capabilities is published with an empty set`() {
        every { repository.findAllWithCapabilities() } returns listOf(contract("IMMEDIATE"))
        val published = slot<AdapterContract>()
        every { producer.send(capture(published)) } just runs

        republisher.republishContracts()

        assertThat(published.captured.eventCapabilities).isEmpty()
    }

    @Test
    fun `nothing is published when there are no contracts`() {
        every { repository.findAllWithCapabilities() } returns emptyList()

        republisher.republishContracts()

        verify(exactly = 0) { producer.send(any()) }
    }

    private fun contract(deltaSyncInterval: String): ContractEntity {
        val contract =
            ContractEntity().apply {
                userName = "adapter@afk.no"
                orgId = "afk.no"
                adapterId = "adapter-1"
                heartbeatIntervalInMinutes = 1
            }
        contract.capabilityEntityset.add(
            CapabilityEntity().apply {
                domainName = "utdanning"
                pkgName = "elev"
                resourceName = "elev"
                fullSyncIntervalInDays = 7
                this.deltaSyncInterval = deltaSyncInterval
                contractEntity = contract
            },
        )
        return contract
    }

    private fun eventCapability(
        contract: ContractEntity,
        resource: String,
        operation: OperationType,
    ): EventCapabilityEntity =
        EventCapabilityEntity().apply {
            domainName = "utdanning"
            pkgName = "vurdering"
            resourceName = resource
            this.operation = operation
            contractEntity = contract
        }
}
