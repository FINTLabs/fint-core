package no.fintlabs.adapter.gateway.event

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.adapter.gateway.config.ProviderProperties
import no.fintlabs.adapter.gateway.event.response.ResponseFintEventProducer
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.org.OrgEntry
import no.novari.core.shared.org.OrgStore
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class EventExpiryServiceTest {
    private val eventStore = mockk<EventStore>()
    private val orgStore = mockk<OrgStore>()
    private val producer = mockk<ResponseFintEventProducer>(relaxed = true)
    private val now = Instant.parse("2026-10-07T08:12:00Z")
    private val service =
        EventExpiryService(
            eventStore,
            orgStore,
            ProviderProperties(orgIdValue = "fintlabs.no"),
            producer,
            Clock.fixed(now, ZoneOffset.UTC),
        )

    @Test
    fun `an expired write is published to the feed, an expired read is not`() {
        every { orgStore.findAll() } returns listOf(OrgEntry("fintlabs.no", now, now))
        every { eventStore.findExpired("fintlabs_no_events", now) } returns
            listOf(request("write", OperationType.CREATE), request("read", OperationType.READ))
        every { eventStore.markExpired(any(), "fintlabs_no_events", now) } returns true

        service.expireOverdueEvents()

        verify(exactly = 1) { producer.publish(match { it.corrId == "write" && it.isFailed }) }
        verify(exactly = 0) { producer.publish(match { it.corrId == "read" }) }
    }

    @Test
    fun `nothing is published for an event another replica flipped first`() {
        every { orgStore.findAll() } returns listOf(OrgEntry("fintlabs.no", now, now))
        every { eventStore.findExpired("fintlabs_no_events", now) } returns listOf(request("write", OperationType.CREATE))
        every { eventStore.markExpired("write", "fintlabs_no_events", now) } returns false

        service.expireOverdueEvents()

        verify(exactly = 0) { producer.publish(any()) }
    }

    private fun request(
        id: String,
        operation: OperationType,
    ): RequestFintEvent =
        RequestFintEvent().apply {
            corrId = id
            orgId = "fintlabs.no"
            domainName = "utdanning"
            packageName = "vurdering"
            resourceName = "elevfravar"
            operationType = operation
            created = now.minusSeconds(1000).toEpochMilli()
            timeToLive = now.minusSeconds(100).toEpochMilli()
        }
}
