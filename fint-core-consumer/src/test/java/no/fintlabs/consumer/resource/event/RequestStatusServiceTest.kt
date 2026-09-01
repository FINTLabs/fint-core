package no.fintlabs.consumer.resource.event

import io.mockk.every
import io.mockk.mockk
import no.fintlabs.adapter.models.event.EventBodyResponse
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.operation.OperationType
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.novari.core.shared.event.EventState
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.StoredEvent
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.IdentifierRef
import no.novari.core.shared.store.ResourceEntry
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.core.model.utdanning.vurdering.Aktivitetsfravar
import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class RequestStatusServiceTest {
    private val eventStore: EventStore = mockk()
    private val resourceStore: ResourceStore = mockk()
    private val now: Instant = Instant.parse("2026-08-25T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val configuration =
        ConsumerConfiguration(
            baseUrl = "https://api.felleskomponent.no",
            orgIdValue = "fintlabs.no",
            domain = "utdanning",
            packageName = "vurdering",
            podUrl = "http://localhost",
        )

    private val service = RequestStatusService(eventStore, resourceStore, configuration, clock)

    private val coordinate = ResourceCoordinate("fintlabs.no", "utdanning", "vurdering", "aktivitetsfravar")
    private val eventCollection = "fintlabs_no_events"
    private val resourceCollection = "fintlabs_no_utdanning_vurdering_aktivitetsfravar"
    private val corrId = "corr-1"

    @Test
    fun `an unknown corrId is gone`() {
        every { eventStore.findByCorrId(corrId, eventCollection) } returns null

        assertThat(service.getStatusResponse(coordinate, corrId)).isEqualTo(RequestGone)
    }

    @Test
    fun `an unanswered event before its deadline is accepted`() {
        givenStored(response = null, deadline = now.plusSeconds(60))

        assertThat(service.getStatusResponse(coordinate, corrId)).isEqualTo(RequestAccepted)
    }

    @Test
    fun `an unanswered event past its deadline fails as expired`() {
        givenStored(response = null, deadline = now.minusSeconds(1))

        val result = service.getStatusResponse(coordinate, corrId)

        assertThat(result).isInstanceOf(RequestFailed::class.java)
        assertThat((result as RequestFailed).failureType).isEqualTo(RequestFailed.FailureType.ERROR)
        assertThat(result.body).isInstanceOf(EventBodyResponse::class.java)
    }

    @Test
    fun `a swept expired event fails as expired without a stored response`() {
        givenStored(response = null, status = EventState.EXPIRED)

        val result = service.getStatusResponse(coordinate, corrId)

        assertThat(result).isInstanceOf(RequestFailed::class.java)
        assertThat((result as RequestFailed).failureType).isEqualTo(RequestFailed.FailureType.ERROR)
        assertThat(result.body).isInstanceOf(EventBodyResponse::class.java)
    }

    @Test
    fun `a failed response maps to error`() {
        givenStored(response = response { isFailed = true })

        val result = service.getStatusResponse(coordinate, corrId) as RequestFailed

        assertThat(result.failureType).isEqualTo(RequestFailed.FailureType.ERROR)
    }

    @Test
    fun `a rejected response maps to rejected`() {
        givenStored(response = response { isRejected = true })

        val result = service.getStatusResponse(coordinate, corrId) as RequestFailed

        assertThat(result.failureType).isEqualTo(RequestFailed.FailureType.REJECTED)
    }

    @Test
    fun `a conflicted response maps to conflict with the adapter's resource as body`() {
        givenStored(
            response =
                response {
                    isConflicted = true
                    value = syncPageEntry()
                },
        )

        val result = service.getStatusResponse(coordinate, corrId) as RequestFailed

        assertThat(result.failureType).isEqualTo(RequestFailed.FailureType.CONFLICT)
        assertThat(result.body).isInstanceOf(Aktivitetsfravar::class.java)
    }

    @Test
    fun `a successful validate is validated`() {
        givenStored(response = response { operationType = OperationType.VALIDATE })

        assertThat(service.getStatusResponse(coordinate, corrId)).isInstanceOf(RequestValidated::class.java)
    }

    @Test
    fun `a successful delete is deleted`() {
        givenStored(response = response { operationType = OperationType.DELETE })

        assertThat(service.getStatusResponse(coordinate, corrId)).isEqualTo(ResourceDeleted)
    }

    @Test
    fun `a successful create stays accepted until the resource store has the entry`() {
        givenStored(response = response { value = syncPageEntry() })
        every { resourceStore.findByResourceId("123", resourceCollection) } returns null

        assertThat(service.getStatusResponse(coordinate, corrId)).isEqualTo(RequestAccepted)
    }

    @Test
    fun `a successful create is created once the store has the entry`() {
        givenStored(response = response { value = syncPageEntry() })
        every { resourceStore.findByResourceId("123", resourceCollection) } returns
            resourceEntry(lastModified = now.minusSeconds(5))

        val result = service.getStatusResponse(coordinate, corrId)

        assertThat(result).isInstanceOf(ResourceCreated::class.java)
        assertThat((result as ResourceCreated).location)
            .isEqualTo(URI.create("https://api.felleskomponent.no/utdanning/vurdering/aktivitetsfravar/systemid/123"))
        assertThat(result.body).isInstanceOf(Aktivitetsfravar::class.java)
    }

    private fun givenStored(
        response: ResponseFintEvent?,
        deadline: Instant = now.plus(Duration.ofMinutes(15)),
        status: EventState = if (response != null) EventState.ANSWERED else EventState.PENDING,
    ) {
        every { eventStore.findByCorrId(corrId, eventCollection) } returns
            StoredEvent(status, request(), response, deadline)
    }

    private fun request(): RequestFintEvent =
        RequestFintEvent().apply {
            corrId = this@RequestStatusServiceTest.corrId
            orgId = "fintlabs.no"
            domainName = "utdanning"
            packageName = "vurdering"
            resourceName = "aktivitetsfravar"
            operationType = OperationType.CREATE
            created = now.minusSeconds(10).toEpochMilli()
            timeToLive = now.plus(Duration.ofMinutes(15)).toEpochMilli()
        }

    private fun response(block: ResponseFintEvent.() -> Unit = {}): ResponseFintEvent =
        ResponseFintEvent()
            .apply {
                corrId = this@RequestStatusServiceTest.corrId
                orgId = "fintlabs.no"
                operationType = OperationType.CREATE
                handledAt = now.minusSeconds(5).toEpochMilli()
            }.apply(block)

    private fun syncPageEntry(): SyncPageEntry =
        SyncPageEntry.of("123", mapOf("systemId" to mapOf("identifikatorverdi" to "123")))

    private fun resourceEntry(lastModified: Instant): ResourceEntry =
        ResourceEntry(
            id = "123",
            data = Document("systemId", Document("identifikatorverdi", "123")),
            identifiers = listOf(IdentifierRef("systemid", "123")),
            createdAt = now.minusSeconds(60),
            lastModified = lastModified,
        )
}
