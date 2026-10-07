package no.fintlabs.client.resource.event

import io.mockk.every
import io.mockk.mockk
import no.fintlabs.adapter.models.event.EventBodyResponse
import no.fintlabs.adapter.models.event.EventIdentifikator
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.operation.OperationType
import no.fintlabs.client.config.ConsumerConfiguration
import no.novari.core.shared.event.EventState
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.StoredEvent
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.RelationEdge
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.store.IdentifierRef
import no.novari.core.shared.store.ResourceEntry
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.core.model.utdanning.elev.Elev
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
        )

    private val relationEdgeStore: RelationEdgeStore = mockk()
    private val service = RequestStatusService(eventStore, resourceStore, relationEdgeStore, configuration, clock)

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
        givenStored(
            request = request(OperationType.VALIDATE),
            response =
                response {
                    operationType =
                        OperationType.VALIDATE
                },
        )

        assertThat(service.getStatusResponse(coordinate, corrId)).isInstanceOf(RequestValidated::class.java)
    }

    @Test
    fun `a successful delete is deleted`() {
        givenStored(
            request = request(OperationType.DELETE),
            response = response { operationType = OperationType.DELETE },
        )

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

    @Test
    fun `a read by filter lists the resources the adapter found`() {
        givenStored(
            request = readRequest(filter = "systemId/identifikatorverdi eq '123'"),
            response = readResponse(syncPageEntry("123"), syncPageEntry("456")),
        )
        givenNoRelationEdges()

        val result = service.getStatusResponse(coordinate, corrId)

        assertThat(result).isInstanceOf(ResourcesRead::class.java)
        val body = (result as ResourcesRead).body
        assertThat(body.embedded.entries).hasSize(2).allMatch { it is Aktivitetsfravar }
        assertThat(body.totalItems).isEqualTo(2)
        assertThat(body.links["self"]!!.single().href)
            .isEqualTo("https://api.felleskomponent.no/utdanning/vurdering/aktivitetsfravar")
    }

    @Test
    fun `a read by filter that found nothing lists nothing`() {
        givenStored(
            request = readRequest(filter = "kommentar eq 'none'"),
            response = readResponse(),
        )

        val result = service.getStatusResponse(coordinate, corrId) as ResourcesRead

        assertThat(result.body.embedded.entries).isEmpty()
        assertThat(result.body.totalItems).isZero()
    }

    @Test
    fun `a read by id answers with the one resource found`() {
        givenStored(
            request = readRequest(id = EventIdentifikator("systemid", "123")),
            response = readResponse(syncPageEntry("123")),
        )
        givenNoRelationEdges()

        val result = service.getStatusResponse(coordinate, corrId)

        assertThat(result).isInstanceOf(ResourceRead::class.java)
        assertThat((result as ResourceRead).body).isInstanceOf(Aktivitetsfravar::class.java)
    }

    @Test
    fun `a read by id that found nothing is not found`() {
        givenStored(
            request = readRequest(id = EventIdentifikator("systemid", "999")),
            response = readResponse(),
        )

        assertThat(service.getStatusResponse(coordinate, corrId)).isEqualTo(ResourceNotRead)
    }

    @Test
    fun `a rejected read fails as rejected with the adapter's reason`() {
        givenStored(
            request = readRequest(filter = "kommentar ne null"),
            response =
                readResponse {
                    isRejected = true
                    rejectReason = "More than 1000 resources match, use a narrower filter"
                },
        )

        val result = service.getStatusResponse(coordinate, corrId) as RequestFailed

        assertThat(result.failureType).isEqualTo(RequestFailed.FailureType.REJECTED)
        assertThat((result.body as EventBodyResponse).message)
            .isEqualTo("More than 1000 resources match, use a narrower filter")
    }

    @Test
    fun `a validate request answered as a create is still validated`() {
        givenStored(
            request = request(operation = OperationType.VALIDATE),
            response = response { operationType = OperationType.CREATE },
        )

        assertThat(service.getStatusResponse(coordinate, corrId)).isInstanceOf(RequestValidated::class.java)
    }

    @Test
    fun `a live result carries the back-links the store holds for it`() {
        val elevCoordinate = ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elev")
        givenStored(
            request = readRequest(id = EventIdentifikator("elevnummer", "E-1")),
            response =
                readResponse(
                    SyncPageEntry.of("E-1", mapOf("elevnummer" to mapOf("identifikatorverdi" to "E-1"))),
                ),
        )
        every {
            relationEdgeStore.findByTargets(
                "fintlabs_no_relation_edges",
                "utdanning/elev/elev",
                listOf(IdentifierRef("elevnummer", "E-1")),
            )
        } returns listOf(elevforholdEdge(sourceId = "EF-1", targetIdField = "elevnummer", targetIdValue = "E-1"))

        val result = service.getStatusResponse(elevCoordinate, corrId) as ResourceRead

        assertThat((result.body as Elev).links["elevforhold"]!!.map { it.idField to it.idValue })
            .containsExactly("systemid" to "EF-1")
    }

    private fun givenStored(
        response: ResponseFintEvent?,
        deadline: Instant = now.plus(Duration.ofMinutes(15)),
        status: EventState = if (response != null) EventState.ANSWERED else EventState.PENDING,
        request: RequestFintEvent = request(),
    ) {
        every { eventStore.findByCorrId(corrId, eventCollection) } returns
            StoredEvent(status, request, response, deadline)
    }

    private fun givenNoRelationEdges() {
        every { relationEdgeStore.findByTargets(any(), any(), any()) } returns emptyList()
    }

    private fun request(operation: OperationType = OperationType.CREATE): RequestFintEvent =
        RequestFintEvent().apply {
            corrId = this@RequestStatusServiceTest.corrId
            orgId = "fintlabs.no"
            domainName = "utdanning"
            packageName = "vurdering"
            resourceName = "aktivitetsfravar"
            operationType = operation
            created = now.minusSeconds(10).toEpochMilli()
            timeToLive = now.plus(Duration.ofMinutes(15)).toEpochMilli()
        }

    private fun readRequest(
        filter: String? = null,
        id: EventIdentifikator? = null,
    ): RequestFintEvent =
        request(OperationType.READ).apply {
            this.filter = filter
            this.id = id
        }

    private fun readResponse(
        vararg found: SyncPageEntry,
        block: ResponseFintEvent.() -> Unit = {},
    ): ResponseFintEvent =
        response {
            operationType = OperationType.READ
            values = found.toList()
        }.apply(block)

    private fun elevforholdEdge(
        sourceId: String,
        targetIdField: String,
        targetIdValue: String,
    ): RelationEdge =
        RelationEdge(
            id = "edge-$sourceId",
            sourceType = "utdanning/elev/elevforhold",
            sourceId = sourceId,
            sourceIdField = "systemid",
            sourceIdValue = sourceId,
            inverseName = "elevforhold",
            targetType = "utdanning/elev/elev",
            targetIdField = targetIdField,
            targetIdValue = targetIdValue,
        )

    private fun response(block: ResponseFintEvent.() -> Unit = {}): ResponseFintEvent =
        ResponseFintEvent()
            .apply {
                corrId = this@RequestStatusServiceTest.corrId
                orgId = "fintlabs.no"
                operationType = OperationType.CREATE
                handledAt = now.minusSeconds(5).toEpochMilli()
            }.apply(block)

    private fun syncPageEntry(id: String = "123"): SyncPageEntry =
        SyncPageEntry.of(id, mapOf("systemId" to mapOf("identifikatorverdi" to id)))

    private fun resourceEntry(lastModified: Instant): ResourceEntry =
        ResourceEntry(
            id = "123",
            data = Document("systemId", Document("identifikatorverdi", "123")),
            identifiers = listOf(IdentifierRef("systemid", "123")),
            createdAt = now.minusSeconds(60),
            lastModified = lastModified,
        )

    private companion object {
    }
}
