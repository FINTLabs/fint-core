package no.fintlabs.adapter.gateway.event.request

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.adapter.gateway.config.EventLongPollProperties
import no.fintlabs.adapter.gateway.register.EventCapabilities
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.event.EventScope
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.model.OrgId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import kotlin.concurrent.thread

class RequestEventServiceTest {
    private val eventStore = mockk<EventStore>()
    private val arrivals = EventArrivals()
    private val service =
        RequestEventService(
            eventStore,
            EventLongPollProperties(
                maxWait = Duration.ofSeconds(5),
                recheckInterval = Duration.ofMillis(50),
                changeStream = EventLongPollProperties.ChangeStream(recheckInterval = Duration.ofSeconds(10)),
            ),
            arrivals,
            Clock.systemUTC(),
        )
    private val orgs = mapOf(OrgId.from("fintlabs.no") to EventCapabilities.NONE)
    private val scope = EventScope.of("utdanning", "elev", "elev")

    @Test
    fun `without a change stream a held poll finds a new event on its next recheck`() {
        every { eventStore.findPending(COLLECTION, any(), scope) } returnsMany listOf(emptyList(), listOf(request()))

        val (events, elapsed) = timed { service.awaitEvents(orgs, scope, 0, Duration.ofSeconds(5)) }

        assertThat(events).hasSize(1)
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1))
    }

    @Test
    fun `with a change stream a held poll sleeps until it is signalled`() {
        arrivals.watched = true
        every { eventStore.findPending(COLLECTION, any(), scope) } returnsMany listOf(emptyList(), listOf(request()))
        val signaller =
            thread {
                Thread.sleep(300)
                arrivals.signal()
            }

        val (events, elapsed) = timed { service.awaitEvents(orgs, scope, 0, Duration.ofSeconds(5)) }
        signaller.join()

        assertThat(events).hasSize(1)
        assertThat(elapsed).isBetween(Duration.ofMillis(250), Duration.ofSeconds(2))
        verify(exactly = 2) { eventStore.findPending(COLLECTION, any(), scope) }
    }

    @Test
    fun `with a change stream and no signal a held poll asks Mongo only when its wait runs out`() {
        arrivals.watched = true
        every { eventStore.findPending(COLLECTION, any(), scope) } returns emptyList()

        val (events, elapsed) = timed { service.awaitEvents(orgs, scope, 0, Duration.ofSeconds(1)) }

        assertThat(events).isEmpty()
        assertThat(elapsed).isBetween(Duration.ofMillis(950), Duration.ofSeconds(3))
        verify(exactly = 2) { eventStore.findPending(COLLECTION, any(), scope) }
    }

    @Test
    fun `a shutdown lets a held poll go at once`() {
        arrivals.watched = true
        every { eventStore.findPending(COLLECTION, any(), scope) } returns emptyList()
        val closer =
            thread {
                Thread.sleep(200)
                service.releaseHeldRequests()
            }

        val (events, elapsed) = timed { service.awaitEvents(orgs, scope, 0, Duration.ofSeconds(5)) }
        closer.join()

        assertThat(events).isEmpty()
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2))
    }

    private fun <T> timed(block: () -> T): Pair<T, Duration> {
        val start = System.nanoTime()
        val result = block()
        return result to Duration.ofNanos(System.nanoTime() - start)
    }

    private fun request(): RequestFintEvent =
        RequestFintEvent().apply {
            corrId = "corr-id"
            orgId = "fintlabs.no"
            domainName = "utdanning"
            packageName = "elev"
            resourceName = "elev"
            operationType = OperationType.CREATE
            created = System.currentTimeMillis()
            timeToLive = created + 60_000
        }

    companion object {
        private const val COLLECTION = "fintlabs_no_events"
    }
}
