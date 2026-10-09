package no.fintlabs.client.resource.event

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fint.antlr.exception.FilterException
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.operation.OperationType
import no.fintlabs.client.resource.ListOptions
import no.fintlabs.client.resource.paging.PageCursor
import no.fintlabs.client.resource.paging.PageDirection
import no.novari.core.shared.event.OrgEventCapabilities
import no.novari.core.shared.event.ResourceOperations
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.PageAnchor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Every row of the case table for a resource that is readable live: the header, the shape of
 * the request and what the org's adapter gateway answers together decide whether a read goes live.
 */
class LiveReadServiceTest {
    private val eventCapabilityClient: EventCapabilityClient = mockk()
    private val requestFintEventService: RequestFintEventService = mockk()
    private val service = LiveReadService(eventCapabilityClient, requestFintEventService)

    private val coordinate = ResourceCoordinate("fintlabs.no", "utdanning", "vurdering", "elevfravar")
    private val filter = "systemId/identifikatorverdi eq '12345'"
    private val event = RequestFintEvent().apply { corrId = "corr-1" }

    @Test
    fun `a filtered read with the header goes live`() {
        givenReadableLive("elevfravar")
        every { requestFintEventService.createRead(coordinate, ReadRequest.ByFilter(filter)) } returns event

        assertThat(service.startByFilter(coordinate, filter, ListOptions(), "respond-async")).isSameAs(event)
    }

    @Test
    fun `the header may carry other preferences and any case`() {
        givenReadableLive("elevfravar")
        every { requestFintEventService.createRead(coordinate, ReadRequest.ByFilter(filter)) } returns event

        assertThat(
            service.startByFilter(coordinate, filter, ListOptions(), "wait=10, Respond-Async; foo=bar"),
        ).isSameAs(event)
    }

    @Test
    fun `a read by id with the header goes live and asks for one resource`() {
        givenReadableLive("elevfravar")
        every { requestFintEventService.createRead(coordinate, ReadRequest.ById("systemid", "12345")) } returns
            event

        assertThat(service.startById(coordinate, "SystemId", "12345", "respond-async")).isSameAs(event)
    }

    @Test
    fun `without the header the cache answers`() {
        givenReadableLive("elevfravar")

        assertThat(service.startByFilter(coordinate, filter, ListOptions(), null)).isNull()
        assertThat(service.startById(coordinate, "systemid", "12345", "wait=10")).isNull()
        verify(exactly = 0) { requestFintEventService.createRead(any(), any()) }
    }

    @Test
    fun `a list read without a filter is answered from the cache`() {
        givenReadableLive("elevfravar")

        assertThat(service.startByFilter(coordinate, null, ListOptions(), "respond-async")).isNull()
        verify(exactly = 0) { requestFintEventService.createRead(any(), any()) }
    }

    @Test
    fun `a list read with any list option is answered from the cache`() {
        givenReadableLive("elevfravar")

        assertThat(
            service.startByFilter(coordinate, filter, ListOptions(size = 10), "respond-async"),
        ).isNull()
        assertThat(
            service.startByFilter(coordinate, filter, ListOptions(offset = 10), "respond-async"),
        ).isNull()
        assertThat(
            service.startByFilter(coordinate, filter, ListOptions(sinceTimeStamp = 1), "respond-async"),
        ).isNull()
        assertThat(
            service.startByFilter(coordinate, filter, ListOptions(cursor = cursor()), "respond-async"),
        ).isNull()
        verify(exactly = 0) { requestFintEventService.createRead(any(), any()) }
    }

    @Test
    fun `a resource no adapter reads live is answered from the cache`() {
        givenReadableLive("elev", packageName = "elev")

        assertThat(service.startByFilter(coordinate, filter, ListOptions(), "respond-async")).isNull()
        assertThat(service.startById(coordinate, "systemid", "12345", "respond-async")).isNull()
        verify(exactly = 0) { requestFintEventService.createRead(any(), any()) }
    }

    @Test
    fun `a resource listed only for writes is answered from the cache`() {
        givenCapabilities(ResourceOperations("utdanning", "vurdering", "elevfravar", setOf(OperationType.CREATE)))

        assertThat(service.startByFilter(coordinate, filter, ListOptions(), "respond-async")).isNull()
    }

    @Test
    fun `a read is answered from the cache when the adapter gateway does not answer`() {
        every { eventCapabilityClient.find(OrgId.from("fintlabs.no")) } returns null

        assertThat(service.startByFilter(coordinate, filter, ListOptions(), "respond-async")).isNull()
    }

    @Test
    fun `a filter that is not valid is refused before any event exists`() {
        givenReadableLive("elevfravar")

        assertThatThrownBy {
            service.startByFilter(
                coordinate,
                "systemId/identifikatorverdi = '12345'",
                ListOptions(),
                "respond-async",
            )
        }.isInstanceOf(FilterException::class.java)
        verify(exactly = 0) { requestFintEventService.createRead(any(), any()) }
    }

    private fun givenReadableLive(
        resourceName: String,
        packageName: String = "vurdering",
    ) = givenCapabilities(ResourceOperations("utdanning", packageName, resourceName, setOf(OperationType.READ)))

    private fun givenCapabilities(vararg resources: ResourceOperations) {
        every { eventCapabilityClient.find(OrgId.from("fintlabs.no")) } returns
            OrgEventCapabilities("fintlabs.no", resources.toList())
    }

    private fun cursor() = PageCursor(PageDirection.AFTER, PageAnchor(Instant.ofEpochMilli(20), "B"))
}
