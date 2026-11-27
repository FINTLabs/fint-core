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
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RequestEventServiceTest {
    private val eventStore = mockk<EventStore>()
    private val eventSignal = EventSignal()
    private val eventInsertWatcher = mockk<EventInsertWatcher>(relaxed = true)
    private val orgs = mapOf(OrgId.from("fintlabs.no") to EventCapabilities.NONE)
    private val scope = EventScope("utdanning")
    private val event = request("stored")

    @Volatile
    private var pending = emptyList<RequestFintEvent>()
    private val firstRead = CountDownLatch(1)

    init {
        every { eventStore.findPending(COLLECTION, any(), scope) } answers {
            pending.also { firstRead.countDown() }
        }
    }

    @Test
    fun `without a wait an empty answer is immediate`() {
        assertThat(service().getEvents(orgs, scope)).isEmpty()

        verify(exactly = 1) { eventStore.findPending(COLLECTION, any(), scope) }
        verify(exactly = 0) { eventInsertWatcher.watch(any()) }
    }

    @Test
    fun `with a wait, the collections of the request are watched`() {
        service().getEvents(orgs, scope, wait = SHORT)

        verify { eventInsertWatcher.watch(listOf(COLLECTION)) }
    }

    @Test
    fun `with a wait, pending events are answered at once`() {
        pending = listOf(event)

        assertThat(service().getEvents(orgs, scope, wait = LONG)).containsExactly(event)

        verify(exactly = 1) { eventStore.findPending(COLLECTION, any(), scope) }
    }

    @Test
    fun `a held request answers with the event after a signal`() {
        val answer = CompletableFuture.supplyAsync { service().getEvents(orgs, scope, wait = LONG) }
        assertThat(firstRead.await(5, TimeUnit.SECONDS)).isTrue

        pending = listOf(event)
        eventSignal.signal(COLLECTION)

        assertThat(answer.get(5, TimeUnit.SECONDS)).containsExactly(event)
    }

    @Test
    fun `a held request reads once more when the wait is over`() {
        every { eventStore.findPending(COLLECTION, any(), scope) } returnsMany listOf(emptyList(), listOf(event))

        assertThat(service().getEvents(orgs, scope, wait = SHORT)).containsExactly(event)

        verify(exactly = 2) { eventStore.findPending(COLLECTION, any(), scope) }
    }

    @Test
    fun `a held request answers empty when the wait is over`() {
        val start = System.nanoTime()

        assertThat(service().getEvents(orgs, scope, wait = SHORT)).isEmpty()

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(SHORT)
    }

    @Test
    fun `a wait above the maximum is cut to the maximum`() {
        val answer = CompletableFuture.supplyAsync { service(maxWait = SHORT).getEvents(orgs, scope, wait = LONG) }

        assertThat(answer.get(5, TimeUnit.SECONDS)).isEmpty()
    }

    @Test
    fun `a held request answers when the gateway stops`() {
        val answer = CompletableFuture.supplyAsync { service().getEvents(orgs, scope, wait = LONG) }
        assertThat(firstRead.await(5, TimeUnit.SECONDS)).isTrue

        eventSignal.stop()

        assertThat(answer.get(5, TimeUnit.SECONDS)).isEmpty()
    }

    private fun service(maxWait: Duration = LONG) =
        RequestEventService(
            eventStore,
            Clock.fixed(Instant.parse("2026-11-19T08:00:00Z"), ZoneOffset.UTC),
            eventSignal,
            eventInsertWatcher,
            EventLongPollProperties(maxWait = maxWait),
        )

    private fun request(id: String): RequestFintEvent =
        RequestFintEvent().apply {
            corrId = id
            orgId = "fintlabs.no"
            domainName = "utdanning"
            packageName = "elev"
            resourceName = "elev"
            operationType = OperationType.CREATE
            created = 1
        }

    private companion object {
        const val COLLECTION = "fintlabs_no_events"
        val SHORT: Duration = Duration.ofMillis(100)
        val LONG: Duration = Duration.ofMinutes(1)
    }
}
