package no.novari.core.shared.event

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.v2.event.EventIdentifier
import no.fintlabs.adapter.models.v2.event.EventOperation
import no.fintlabs.adapter.models.v2.event.EventRequest
import no.fintlabs.adapter.models.v2.event.EventResponse
import no.fintlabs.adapter.models.v2.event.EventStatus
import no.fintlabs.adapter.operation.OperationType
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventDocumentTest {
    private val created = Instant.parse("2026-08-26T12:00:00Z")
    private val deadline = created.plusSeconds(900)
    private val expireAt = created.plusSeconds(1_800)
    private val json = JsonMapper.builder().build()

    private val request =
        EventRequest
            .builder()
            .corrId("corr-1")
            .orgId("fintlabs.no")
            .domainName("utdanning")
            .packageName("vurdering")
            .resourceName("aktivitetsfravar")
            .operation(EventOperation.CREATE)
            .created(created.toEpochMilli())
            .deadline(deadline.toEpochMilli())
            .value("""{"systemId":{"identifikatorverdi":"42"}}""")
            .build()

    @Test
    fun `a new document is pending and carries the request's timestamps and operation`() {
        val document = request.toEventDocument(expireAt)

        assertEquals("corr-1", document.corrId)
        assertEquals(EventState.PENDING, document.status)
        assertEquals("fintlabs.no", document.orgId)
        assertEquals("utdanning", document.domainName)
        assertEquals("vurdering", document.packageName)
        assertEquals("aktivitetsfravar", document.resourceName)
        assertEquals(created, document.created)
        assertEquals(deadline, document.deadline)
        assertEquals(expireAt, document.expireAt)
        assertEquals(CURRENT_FORMAT, document.format)
        assertEquals(EventOperation.CREATE, document.operation)
        assertNull(document.response)
        assertNull(document.handledAt)
    }

    @Test
    fun `the request survives the round trip through the stored JSON`() {
        val stored = request.toEventDocument(expireAt).toStoredEvent()

        assertEquals(EventState.PENDING, stored.status)
        assertEquals(deadline, stored.deadline)
        assertNull(stored.response)
        assertEquals(request, stored.request)
    }

    @Test
    fun `a read request keeps its filter, id and maxResults`() {
        val read =
            EventRequest
                .builder()
                .corrId("corr-2")
                .orgId("fintlabs.no")
                .domainName("utdanning")
                .packageName("elev")
                .resourceName("elev")
                .operation(EventOperation.READ)
                .filter("navn/fornavn eq 'Ola'")
                .id(EventIdentifier("fodselsnummer", "12345678901"))
                .maxResults(1000)
                .build()

        assertEquals(read, read.toEventDocument(expireAt).toStoredEvent().request)
    }

    @Test
    fun `the response survives the round trip including its resources`() {
        val response =
            EventResponse
                .builder()
                .corrId("corr-1")
                .orgId("fintlabs.no")
                .status(EventStatus.SUCCEEDED)
                .resources(listOf(SyncPageEntry.of("42", mapOf("systemId" to mapOf("identifikatorverdi" to "42")))))
                .build()

        val stored =
            request
                .toEventDocument(expireAt)
                .copy(status = EventState.ANSWERED, response = response.toStoredJson())
                .toStoredEvent()

        assertEquals(EventState.ANSWERED, stored.status)
        assertEquals(EventStatus.SUCCEEDED, stored.response?.status)
        assertEquals(
            "42",
            stored.response
                ?.resources
                ?.single()
                ?.identifier,
        )
        assertEquals(
            mapOf("systemId" to mapOf("identifikatorverdi" to "42")),
            stored.response
                ?.resources
                ?.single()
                ?.resource,
        )
    }

    @Test
    fun `a document written before the v2 shape is still read`() {
        val v1Request =
            RequestFintEvent().apply {
                corrId = "corr-1"
                orgId = "fintlabs.no"
                domainName = "utdanning"
                packageName = "vurdering"
                resourceName = "aktivitetsfravar"
                operationType = OperationType.UPDATE
                created = this@EventDocumentTest.created.toEpochMilli()
                timeToLive = deadline.toEpochMilli()
                value = """{"systemId":{"identifikatorverdi":"42"}}"""
            }
        val v1Response =
            ResponseFintEvent().apply {
                corrId = "corr-1"
                orgId = "fintlabs.no"
                operationType = OperationType.UPDATE
                isRejected = true
                rejectReason = "Missing field"
                value = SyncPageEntry.of("42", mapOf("navn" to "x"))
            }

        val stored =
            request
                .toEventDocument(expireAt)
                .copy(
                    status = EventState.ANSWERED,
                    request = json.writeValueAsString(v1Request),
                    response = json.writeValueAsString(v1Response),
                    format = null,
                    operation = null,
                ).toStoredEvent()

        assertEquals(EventOperation.UPDATE, stored.request.operation)
        assertEquals(deadline.toEpochMilli(), stored.request.deadline)
        assertEquals(v1Request.value, stored.request.value)
        assertEquals(EventStatus.REJECTED, stored.response?.status)
        assertEquals("Missing field", stored.response?.message)
        assertEquals(
            "42",
            stored.response
                ?.resources
                ?.single()
                ?.identifier,
        )
    }

    @Test
    fun `unknown fields in stored JSON are ignored when reading`() {
        val document =
            request
                .toEventDocument(expireAt)
                .copy(request = """{"corrId":"corr-1","orgId":"fintlabs.no","futureField":true}""")

        assertEquals("corr-1", document.parseRequest().corrId)
    }
}
