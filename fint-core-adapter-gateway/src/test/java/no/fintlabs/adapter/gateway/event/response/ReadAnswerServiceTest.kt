package no.fintlabs.adapter.gateway.event.response

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import no.fintlabs.adapter.gateway.event.InvalidResponseFintEventException
import no.fintlabs.adapter.gateway.event.NoRequestFoundException
import no.fintlabs.adapter.gateway.event.ReadAnswerTooLargeException
import no.fintlabs.adapter.models.event.EventIdentifikator
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.event.ClaimOutcome
import no.novari.core.shared.event.EventStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ReadAnswerServiceTest {
    private val eventStore = mockk<EventStore>()
    private val service = ReadAnswerService(eventStore)
    private val collection = "fintlabs_no_events"

    @Test
    fun `a good answer is stored on the event with every resource in storage form`() {
        every { eventStore.markAnswered(any(), collection) } returns ClaimOutcome.Claimed
        val answer = answer(elevfravar("1"), elevfravar("2"))

        service.accept(readByFilter(), answer, collection)

        val stored = slot<ResponseFintEvent>()
        verify { eventStore.markAnswered(capture(stored), collection) }
        assertThat(stored.captured.values.map { it.identifier }).containsExactly("1", "2")
        assertThat((stored.captured.values[0].resource as Map<*, *>)["systemId"])
            .isEqualTo(mapOf("identifikatorverdi" to "1"))
    }

    @Test
    fun `a resource the adapter sent with links is stored without a self link and with id-based links`() {
        every { eventStore.markAnswered(any(), collection) } returns ClaimOutcome.Claimed
        val resource =
            mapOf(
                "systemId" to mapOf("identifikatorverdi" to "1"),
                "_links" to
                    mapOf(
                        "self" to listOf(mapOf("href" to "https://api.felleskomponent.no/utdanning/vurdering/elevfravar/systemid/1")),
                        "elevforhold" to listOf(mapOf("href" to "https://api.felleskomponent.no/utdanning/elev/elevforhold/systemid/EF-1")),
                    ),
            )

        service.accept(readByFilter(), answer(SyncPageEntry.of("1", resource)), collection)

        val stored = slot<ResponseFintEvent>()
        verify { eventStore.markAnswered(capture(stored), collection) }
        val links = (stored.captured.values[0].resource as Map<*, *>)["_links"] as Map<*, *>
        assertThat(links.keys).doesNotContain("self")
        assertThat(links["elevforhold"]).isEqualTo(listOf(mapOf("idField" to "systemid", "idValue" to "EF-1")))
    }

    @Test
    fun `a read by id with two resources is refused`() {
        assertThatThrownBy { service.accept(readById(), answer(elevfravar("1"), elevfravar("2")), collection) }
            .isInstanceOf(InvalidResponseFintEventException::class.java)
            .hasMessageContaining("at most one")
    }

    @Test
    fun `a conflicted read answer is refused`() {
        assertThatThrownBy { service.accept(readByFilter(), answer().apply { isConflicted = true }, collection) }
            .isInstanceOf(InvalidResponseFintEventException::class.java)
            .hasMessageContaining("conflicted")
    }

    @Test
    fun `a rejected read answer that still carries resources is refused`() {
        val answer = answer(elevfravar("1")).apply { isRejected = true }

        assertThatThrownBy { service.accept(readByFilter(), answer, collection) }
            .isInstanceOf(InvalidResponseFintEventException::class.java)
            .hasMessageContaining("no values")
    }

    @Test
    fun `a rejected read answer without resources is stored`() {
        every { eventStore.markAnswered(any(), collection) } returns ClaimOutcome.Claimed
        val answer =
            answer().apply {
                isRejected = true
                rejectReason = "Use a narrower filter"
            }

        service.accept(readByFilter(), answer, collection)

        verify { eventStore.markAnswered(match { it.isRejected && it.values.isEmpty() }, collection) }
    }

    @Test
    fun `a read answer that uses value instead of values is refused`() {
        val answer = answer().apply { value = elevfravar("1") }

        assertThatThrownBy { service.accept(readByFilter(), answer, collection) }
            .isInstanceOf(InvalidResponseFintEventException::class.java)
            .hasMessageContaining("values, not in value")
    }

    @Test
    fun `a resource that is not a valid resource of the type is refused`() {
        val answer = answer(SyncPageEntry.of("1", mapOf("systemId" to "not-an-identifikator")))

        assertThatThrownBy { service.accept(readByFilter(), answer, collection) }
            .isInstanceOf(InvalidResponseFintEventException::class.java)
            .hasMessageContaining("not a valid elevfravar")
    }

    @Test
    fun `an answer larger than 8 MB closes the event as rejected and is refused as too large`() {
        every { eventStore.markAnswered(any(), collection) } returns ClaimOutcome.Claimed
        val huge = elevfravar("x".repeat(9 * 1024 * 1024))

        assertThatThrownBy { service.accept(readByFilter(), answer(huge), collection) }
            .isInstanceOf(ReadAnswerTooLargeException::class.java)

        verify {
            eventStore.markAnswered(
                match { it.isRejected && it.values.isEmpty() && it.rejectReason.contains("narrower filter") },
                collection,
            )
        }
    }

    @Test
    fun `an answer that loses the claim is not found`() {
        every { eventStore.markAnswered(any(), collection) } returns ClaimOutcome.AlreadyAnswered

        assertThatThrownBy { service.accept(readByFilter(), answer(elevfravar("1")), collection) }
            .isInstanceOf(NoRequestFoundException::class.java)
    }

    private fun readByFilter(): RequestFintEvent =
        read().apply {
            filter = "systemId/identifikatorverdi eq '1'"
        }

    private fun readById(): RequestFintEvent =
        read().apply {
            id = EventIdentifikator("systemid", "1")
        }

    private fun read(): RequestFintEvent =
        RequestFintEvent().apply {
            corrId = "corr-1"
            orgId = "fintlabs.no"
            domainName = "utdanning"
            packageName = "vurdering"
            resourceName = "elevfravar"
            operationType = OperationType.READ
            created = 1_791_278_657_000
            timeToLive = created + 900_000
        }

    private fun answer(vararg entries: SyncPageEntry): ResponseFintEvent =
        ResponseFintEvent().apply {
            corrId = "corr-1"
            orgId = "fintlabs.no"
            operationType = OperationType.READ
            handledAt = 1_791_278_700_000
            values = entries.toMutableList()
        }

    private fun elevfravar(id: String): SyncPageEntry = SyncPageEntry.of(id, mapOf("systemId" to mapOf("identifikatorverdi" to id)))
}
