package no.novari.core.shared.event

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.operation.OperationType
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventDocumentTest {
    private val created = Instant.parse("2026-08-26T12:00:00Z")
    private val deadline = created.plusSeconds(900)
    private val expireAt = created.plusSeconds(1_800)

    private val request =
        RequestFintEvent().apply {
            corrId = "corr-1"
            orgId = "fintlabs.no"
            domainName = "utdanning"
            packageName = "vurdering"
            resourceName = "aktivitetsfravar"
            operationType = OperationType.CREATE
            this.created = this@EventDocumentTest.created.toEpochMilli()
            timeToLive = deadline.toEpochMilli()
            value = """{"systemId":{"identifikatorverdi":"42"}}"""
        }

    @Test
    fun `a new document is pending and carries the request's timestamps`() {
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
        assertNull(document.response)
        assertNull(document.handledAt)
    }

    @Test
    fun `the request survives the round trip through the stored JSON`() {
        val stored = request.toEventDocument(expireAt).toStoredEvent()

        assertEquals(EventState.PENDING, stored.status)
        assertEquals(deadline, stored.deadline)
        assertNull(stored.response)
        assertEquals("corr-1", stored.request.corrId)
        assertEquals(OperationType.CREATE, stored.request.operationType)
        assertEquals(request.value, stored.request.value)
        assertEquals(request.timeToLive, stored.request.timeToLive)
    }

    @Test
    fun `the response survives the round trip including its arbitrary resource payload`() {
        val response =
            ResponseFintEvent().apply {
                corrId = "corr-1"
                orgId = "fintlabs.no"
                operationType = OperationType.CREATE
                handledAt = deadline.minusSeconds(10).toEpochMilli()
                value = SyncPageEntry.of("42", mapOf("systemId" to mapOf("identifikatorverdi" to "42")))
            }

        val document =
            request.toEventDocument(expireAt).copy(
                status = EventState.ANSWERED,
                response = response.toStoredJson(),
            )
        val stored = document.toStoredEvent()

        assertEquals(EventState.ANSWERED, stored.status)
        assertEquals(response.handledAt, stored.response?.handledAt)
        assertEquals("42", stored.response?.value?.identifier)
        assertEquals(
            mapOf("systemId" to mapOf("identifikatorverdi" to "42")),
            stored.response?.value?.resource,
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
