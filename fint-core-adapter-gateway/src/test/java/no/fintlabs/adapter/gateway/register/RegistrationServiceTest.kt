package no.fintlabs.adapter.gateway.register

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import no.fintlabs.adapter.models.AdapterCapability
import no.fintlabs.adapter.models.AdapterContract
import no.novari.core.shared.org.OrgStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RegistrationServiceTest {
    private val producer: AdapterContractProducer = mockk()
    private val contractService: ContractService = mockk()
    private val orgStore: OrgStore = mockk(relaxed = true)
    private val registrationService = RegistrationService(producer, contractService, orgStore)

    private val published = slot<AdapterContract>()
    private val saved = slot<AdapterContract>()

    init {
        every { producer.send(capture(published)) } just runs
        every { contractService.saveContract(capture(saved)) } just runs
    }

    @Test
    fun `a null heartbeat interval is published and stored as 0`() {
        registrationService.register(contract(heartbeat = null))

        assertThat(published.captured.heartbeatIntervalInMinutes).isEqualTo(0)
        assertThat(saved.captured.heartbeatIntervalInMinutes).isEqualTo(0)
    }

    @Test
    fun `a heartbeat interval is published as sent, also outside one to five minutes`() {
        registrationService.register(contract(heartbeat = 10))

        assertThat(published.captured.heartbeatIntervalInMinutes).isEqualTo(10)
    }

    @Test
    fun `a missing or blank adapter id is published and stored as the username`() {
        listOf(null, "", " ").forEach { adapterId ->
            registrationService.register(contract(adapterId = adapterId))

            assertThat(published.captured.adapterId).isEqualTo("adapter@afk.no")
            assertThat(saved.captured.adapterId).isEqualTo("adapter@afk.no")
        }
    }

    @Test
    fun `an adapter id is published as sent`() {
        registrationService.register(contract(adapterId = "https://vendor.no/afk.no/utdanning"))

        assertThat(published.captured.adapterId).isEqualTo("https://vendor.no/afk.no/utdanning")
    }

    @Test
    fun `a resource listed twice is kept once, with the shortest full sync interval`() {
        val contract =
            contract(
                capabilities =
                    setOf(
                        capability("elev", "elev", fullSyncIntervalInDays = 7),
                        capability("elev", "Elev", fullSyncIntervalInDays = 2),
                        capability("elev", "ELEV", fullSyncIntervalInDays = 4),
                        capability("elev", "basisgruppe", fullSyncIntervalInDays = 1),
                    ),
            )

        registrationService.register(contract)

        assertThat(published.captured.capabilities.map { it.resourceName to it.fullSyncIntervalInDays })
            .containsExactlyInAnyOrder("Elev" to 2, "basisgruppe" to 1)
        assertThat(saved.captured.capabilities).isEqualTo(published.captured.capabilities)
    }

    private fun contract(
        adapterId: String? = "adapter",
        heartbeat: Int? = 3,
        capabilities: Set<AdapterCapability> = emptySet(),
    ): AdapterContract =
        AdapterContract
            .builder()
            .adapterId(adapterId)
            .orgId("afk.no")
            .username("adapter@afk.no")
            .heartbeatIntervalInMinutes(heartbeat)
            .capabilities(capabilities)
            .build()

    private fun capability(
        pkg: String,
        resource: String,
        fullSyncIntervalInDays: Int,
    ): AdapterCapability =
        AdapterCapability
            .builder()
            .domainName("utdanning")
            .packageName(pkg)
            .resourceName(resource)
            .fullSyncIntervalInDays(fullSyncIntervalInDays)
            .build()
}
