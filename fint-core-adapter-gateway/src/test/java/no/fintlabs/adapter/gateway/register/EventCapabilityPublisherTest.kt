package no.fintlabs.adapter.gateway.register

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.event.EventCapabilityStore
import no.novari.core.shared.event.OrgEventCapabilities
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.resourceRefOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class EventCapabilityPublisherTest {
    private val contractService = mockk<ContractService>()
    private val eventCapabilityStore = mockk<EventCapabilityStore>(relaxed = true)
    private val now = Instant.parse("2026-10-07T08:12:00Z")
    private val publisher = EventCapabilityPublisher(contractService, eventCapabilityStore, Clock.fixed(now, ZoneOffset.UTC))

    private val orgId = OrgId.from("fintlabs.no")
    private val elevfravar = resourceRefOf("utdanning", "vurdering", "elevfravar")
    private val fravar = resourceRefOf("utdanning", "vurdering", "fravar")

    @Test
    fun `the org's document is the union of what its adapters listed`() {
        every { contractService.contractsFor(orgId) } returns
            listOf(
                contract(elevfravar to setOf(OperationType.READ)),
                contract(elevfravar to setOf(OperationType.CREATE), fravar to setOf(OperationType.READ)),
            )

        publisher.publish(orgId)

        val saved = savedDocument()
        assertThat(saved.orgId).isEqualTo("fintlabs.no")
        assertThat(saved.updatedAt).isEqualTo(now)
        assertThat(saved.operationsFor(elevfravar)).containsExactlyInAnyOrder(OperationType.READ, OperationType.CREATE)
        assertThat(saved.operationsFor(fravar)).containsExactly(OperationType.READ)
        assertThat(saved.canRead(resourceRefOf("utdanning", "vurdering", "karakter"))).isFalse()
    }

    @Test
    fun `an org whose adapters list nothing gets an empty document`() {
        every { contractService.contractsFor(orgId) } returns listOf(contract())

        publisher.publish(orgId)

        assertThat(savedDocument().resources).isEmpty()
    }

    @Test
    fun `on start every registered org is published`() {
        val other = OrgId.from("test.fintlabs.no")
        every { contractService.registeredOrgs() } returns listOf(orgId, other)
        every { contractService.contractsFor(any()) } returns listOf(contract(elevfravar to setOf(OperationType.READ)))

        publisher.publishAll()

        verify { eventCapabilityStore.save(match { it.orgId == "fintlabs.no" }) }
        verify { eventCapabilityStore.save(match { it.orgId == "test.fintlabs.no" }) }
    }

    private fun savedDocument(): OrgEventCapabilities {
        val saved = slot<OrgEventCapabilities>()
        verify { eventCapabilityStore.save(capture(saved)) }
        return saved.captured
    }

    private fun contract(vararg listed: Pair<no.novari.fint.core.model.FintResourceRef, Set<OperationType>>): RegisteredContract =
        RegisteredContract(orgId, emptySet(), EventCapabilities(listed.toMap()))
}
