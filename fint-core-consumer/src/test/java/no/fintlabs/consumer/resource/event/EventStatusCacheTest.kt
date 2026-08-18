package no.fintlabs.consumer.resource.event

import io.mockk.every
import io.mockk.mockk
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.consumer.config.EventCacheProperties
import no.fintlabs.consumer.config.EventCacheProperties.LifeCycle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class EventStatusCacheTest {
    private val fixedInstant = Instant.parse("2023-10-01T12:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneId.of("UTC"))

    private val resourceName = "student"

    private lateinit var properties: EventCacheProperties
    private lateinit var eventStatusCache: EventStatusCache

    @BeforeEach
    fun setup() {
        val defaultLifeCycle = LifeCycle(eviction = Duration.ofMinutes(30))
        val studentLifeCycle = LifeCycle(eviction = Duration.ofMinutes(60))

        properties =
            mockk {
                every { getLifeCycleConfig(resourceName) } returns studentLifeCycle
                every { getLifeCycleConfig(neq(resourceName)) } returns defaultLifeCycle
            }

        eventStatusCache = EventStatusCache(properties, clock)
    }

    @Test
    fun `should track request if it is within retention time`() {
        // Arrange
        // Created 10 mins ago. Retention for resourceName is 60 mins. (Remaining: 50 mins)
        val created = fixedInstant.minus(Duration.ofMinutes(10)).toEpochMilli()
        val request = createRequest(resourceName, "id-1", created)

        // Act
        eventStatusCache.trackRequest(request)

        // Assert
        assertTrue(eventStatusCache.requestExists("id-1"))
    }

    @Test
    fun `should NOT track request if it is older than retention time`() {
        // Arrange
        // Created 70 mins ago. Retention for resourceName is 60 mins. (Expired by 10 mins)
        val created = fixedInstant.minus(Duration.ofMinutes(70)).toEpochMilli()
        val request = createRequest(resourceName, "id-2", created)

        // Act
        eventStatusCache.trackRequest(request)

        // Assert
        assertFalse(eventStatusCache.requestExists("id-2"))
    }

    @Test
    fun `should purge response if request arrives expired`() {
        // Arrange
        val corrId = "id-3"

        // Simulate a response sitting in the cache (maybe it arrived before the request)
        val response = createResponse(corrId, fixedInstant.toEpochMilli())
        eventStatusCache.trackResponse(corrId, response)
        assertNotNull(eventStatusCache.getResponse(corrId))

        // Request arrives, but it's ancient (Expired)
        val created = fixedInstant.minus(Duration.ofMinutes(70)).toEpochMilli()
        val request = createRequest(resourceName, corrId, created)

        // Act
        eventStatusCache.trackRequest(request)

        // Assert
        assertNull(eventStatusCache.getResponse(corrId), "Response should be invalidated immediately")
        assertFalse(eventStatusCache.requestExists(corrId), "Expired request should not be cached")
    }

    @Test
    fun `should use default config for unknown resources`() {
        // Arrange
        // Note: This would be valid for resourceName (60m), but is expired for "default" (30m).
        val created = fixedInstant.minus(Duration.ofMinutes(40)).toEpochMilli()
        val request = createRequest("unknown", "id-4", created)

        // Act
        eventStatusCache.trackRequest(request)

        // Assert
        assertFalse(eventStatusCache.requestExists("id-4"), "Should use default retention and expire")
    }

    @Test
    fun `should track response if handled within safety limit`() {
        // Arrange
        // Handled 11 hours ago (Safety limit is 12 hours)
        val handled = fixedInstant.minus(Duration.ofHours(11)).toEpochMilli()
        val response = createResponse("id-5", handled)

        // Act
        eventStatusCache.trackResponse("id-5", response)

        // Assert
        assertEquals(response, eventStatusCache.getResponse("id-5"))
    }

    @Test
    fun `should ignore response if handled outside safety limit`() {
        // Arrange
        // Handled 13 hours ago (Safety limit is 12 hours)
        val handled = fixedInstant.minus(Duration.ofHours(13)).toEpochMilli()
        val response = createResponse("id-6", handled)

        // Act
        eventStatusCache.trackResponse("id-6", response)

        // Assert
        assertNull(eventStatusCache.getResponse("id-6"))
    }

    // -- Helpers --

    private fun createRequest(
        resourceName: String,
        corrId: String,
        created: Long,
    ): RequestFintEvent =
        RequestFintEvent
            .builder()
            .corrId(corrId)
            .resourceName(resourceName)
            .created(created)
            .build()

    private fun createResponse(
        corrId: String,
        handledAt: Long,
    ): ResponseFintEvent =
        ResponseFintEvent
            .builder()
            .corrId(corrId)
            .handledAt(handledAt)
            .value(SyncPageEntry.of("sys-id", "val"))
            .build()
}
