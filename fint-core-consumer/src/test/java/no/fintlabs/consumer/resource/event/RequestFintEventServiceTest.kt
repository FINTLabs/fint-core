package no.fintlabs.consumer.resource.event

import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.adapter.operation.OperationType
import no.fintlabs.consumer.config.EventProperties
import no.fintlabs.consumer.kafka.event.RequestFintEventProducer
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.json.FintJson
import no.novari.core.shared.model.ResourceCoordinate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class RequestFintEventServiceTest {
    private val eventStore: EventStore = mockk(relaxed = true)
    private val producer: RequestFintEventProducer = mockk(relaxed = true)
    private val eventProperties = EventProperties()
    private val now: Instant = Instant.parse("2026-08-25T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val responseMapper = FintJson.responseMapper("https://api.felleskomponent.no")

    private val service = RequestFintEventService(eventStore, eventProperties, producer, responseMapper, clock)

    private val coordinate = ResourceCoordinate("fintlabs.no", "utdanning", "vurdering", "aktivitetsfravar")

    private val resourceData =
        mapOf(
            "systemId" to mapOf("identifikatorverdi" to "42"),
            "kommentar" to "test",
        )

    @Test
    fun `builds the event from the coordinate and stores it before publishing`() {
        val event = service.createAndPublish(coordinate, resourceData, validateOnly = false)

        assertThat(event.corrId).isNotBlank
        assertThat(event.orgId).isEqualTo("fintlabs.no")
        assertThat(event.domainName).isEqualTo("utdanning")
        assertThat(event.packageName).isEqualTo("vurdering")
        assertThat(event.resourceName).isEqualTo("aktivitetsfravar")
        assertThat(event.operationType).isEqualTo(OperationType.CREATE)
        assertThat(event.created).isEqualTo(now.toEpochMilli())
        assertThat(event.timeToLive).isEqualTo(now.plus(Duration.ofMinutes(15)).toEpochMilli())
        assertThat(event.value).contains("\"identifikatorverdi\":\"42\"")
        assertThat(event.value)
            .contains("\"href\":\"https://api.felleskomponent.no/utdanning/vurdering/aktivitetsfravar/systemid/42\"")

        verify { eventStore.save(event, now.plus(Duration.ofMinutes(30)), "fintlabs_no_events") }
        verify { producer.publish(event) }
    }

    @Test
    fun `validateOnly creates a validate event`() {
        val event = service.createAndPublish(coordinate, resourceData, validateOnly = true)

        assertThat(event.operationType).isEqualTo(OperationType.VALIDATE)
    }

    @Test
    fun `update operation is passed through`() {
        val event = service.createAndPublish(coordinate, resourceData, OperationType.UPDATE)

        assertThat(event.operationType).isEqualTo(OperationType.UPDATE)
    }

    @Test
    fun `a missing body leaves the value empty`() {
        val event = service.createAndPublish(coordinate, null, OperationType.UPDATE)

        assertThat(event.value).isNull()
    }
}
