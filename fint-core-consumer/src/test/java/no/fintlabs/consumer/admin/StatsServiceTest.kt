package no.fintlabs.consumer.admin

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.org.OrgEntry
import no.novari.core.shared.org.OrgStore
import no.novari.core.shared.store.ResourceStore
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatsServiceTest {
    private val resourceStore = mockk<ResourceStore>()
    private val orgStore = mockk<OrgStore>()
    private val statsService = StatsService(consumerConfiguration(), resourceStore, orgStore)

    @Test
    fun `getLastUpdated returns epoch millis from resource store`() {
        val coordinate = ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elev")
        val lastUpdated = Instant.parse("2026-01-02T03:04:05Z")
        every { resourceStore.getLastUpdated(coordinate) } returns lastUpdated

        val result = statsService.getLastUpdated(coordinate)

        assertEquals(lastUpdated.toEpochMilli(), result)
    }

    @Test
    fun `getLastUpdated returns 0 when resource store has no timestamp`() {
        val coordinate = ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elev")
        every { resourceStore.getLastUpdated(coordinate) } returns null

        val result = statsService.getLastUpdated(coordinate)

        assertEquals(0L, result)
    }

    @Test
    fun `getCacheSize returns int value from resource store`() {
        val coordinate = ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elev")
        every { resourceStore.getCacheSize(coordinate) } returns 42L

        val result = statsService.getCacheSize(coordinate)

        assertEquals(42, result)
    }

    @Test
    fun `cacheStatus returns status for resources in every registered org`() {
        val fintlabsElevLastUpdated = Instant.parse("2026-02-03T04:05:06Z")
        val testOrgPersonLastUpdated = Instant.parse("2026-03-04T05:06:07Z")

        every { orgStore.findAll() } returns
            listOf(
                OrgEntry(id = "fintlabs.no"),
                OrgEntry(id = "test.org.no"),
            )

        val sizesByOrgAndResource =
            mapOf(
                ("fintlabs.no" to "elev") to 2L,
                ("fintlabs.no" to "person") to 1L,
                ("test.org.no" to "elev") to 5L,
                ("test.org.no" to "person") to 3L,
            )

        val lastUpdatedByOrgAndResource =
            mapOf(
                ("fintlabs.no" to "elev") to fintlabsElevLastUpdated,
                ("test.org.no" to "person") to testOrgPersonLastUpdated,
            )

        every { resourceStore.getCacheSize(any()) } answers {
            val coordinate = firstArg<ResourceCoordinate>()
            sizesByOrgAndResource[coordinate.orgId to coordinate.resourceName] ?: 0L
        }
        every { resourceStore.getLastUpdated(any()) } answers {
            val coordinate = firstArg<ResourceCoordinate>()
            lastUpdatedByOrgAndResource[coordinate.orgId to coordinate.resourceName]
        }

        val result = statsService.cacheStatus("utdanning", "elev")

        assertEquals(2, result.size)

        val fintlabs = result.single { it.orgId == "fintlabs.no" }
        assertEquals(Date.from(fintlabsElevLastUpdated), fintlabs.caches.getValue("elev").lastUpdated())
        assertEquals(2, fintlabs.caches.getValue("elev").size())
        assertEquals(1, fintlabs.caches.getValue("person").size())
        assertNull(fintlabs.caches["fravarsregistrering"])

        val testOrg = result.single { it.orgId == "test.org.no" }
        assertEquals(5, testOrg.caches.getValue("elev").size())
        assertEquals(Date.from(testOrgPersonLastUpdated), testOrg.caches.getValue("person").lastUpdated())
        assertEquals(3, testOrg.caches.getValue("person").size())

        verify(exactly = 1) { orgStore.findAll() }
        verify {
            resourceStore.getCacheSize(ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elev"))
            resourceStore.getLastUpdated(ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elev"))
            resourceStore.getCacheSize(ResourceCoordinate("fintlabs.no", "utdanning", "elev", "person"))
            resourceStore.getLastUpdated(ResourceCoordinate("fintlabs.no", "utdanning", "elev", "person"))
            resourceStore.getCacheSize(ResourceCoordinate("test.org.no", "utdanning", "elev", "elev"))
            resourceStore.getLastUpdated(ResourceCoordinate("test.org.no", "utdanning", "elev", "elev"))
            resourceStore.getCacheSize(ResourceCoordinate("test.org.no", "utdanning", "elev", "person"))
            resourceStore.getLastUpdated(ResourceCoordinate("test.org.no", "utdanning", "elev", "person"))
        }
    }

    private fun consumerConfiguration() =
        ConsumerConfiguration(
            baseUrl = "https://api.felleskomponent.no",
            orgIdValue = "fintlabs.no",
            domain = "utdanning",
            packageName = "elev",
            podUrl = "http://localhost",
        )
}
