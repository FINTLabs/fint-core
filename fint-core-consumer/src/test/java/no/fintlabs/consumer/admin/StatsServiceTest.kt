package no.fintlabs.consumer.admin

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.ResourceStore
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsServiceTest {
    private val resourceStore = mockk<ResourceStore>()
    private val statsService = StatsService(consumerConfiguration(), resourceStore)

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
    fun `cacheStatus returns status for resources in the configured component`() {
        val elevLastUpdated = Instant.parse("2026-02-03T04:05:06Z")
        val personLastUpdated = Instant.parse("2026-03-04T05:06:07Z")
        val sizesByResource =
            mapOf(
                "elev" to 2L,
                "person" to 1L,
            )
        val lastUpdatedByResource =
            mapOf(
                "elev" to elevLastUpdated,
                "person" to personLastUpdated,
            )

        every { resourceStore.getCacheSize(any()) } answers {
            sizesByResource[firstArg<ResourceCoordinate>().resourceName] ?: 0L
        }
        every { resourceStore.getLastUpdated(any()) } answers {
            lastUpdatedByResource[firstArg<ResourceCoordinate>().resourceName]
        }

        val result = statsService.cacheStatus()

        assertTrue(result.containsKey("elev"))
        assertEquals(Date.from(elevLastUpdated), result.getValue("elev").lastUpdated())
        assertEquals(2, result.getValue("elev").size())

        assertTrue(result.containsKey("person"))
        assertEquals(Date.from(personLastUpdated), result.getValue("person").lastUpdated())
        assertEquals(1, result.getValue("person").size())

        assertNull(result["fravarsregistrering"])
        verify {
            resourceStore.getCacheSize(ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elev"))
            resourceStore.getLastUpdated(ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elev"))
            resourceStore.getCacheSize(ResourceCoordinate("fintlabs.no", "utdanning", "elev", "person"))
            resourceStore.getLastUpdated(ResourceCoordinate("fintlabs.no", "utdanning", "elev", "person"))
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
