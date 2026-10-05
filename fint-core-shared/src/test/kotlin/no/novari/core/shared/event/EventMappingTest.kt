package no.novari.core.shared.event

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.v2.event.EventOperation
import no.fintlabs.adapter.models.v2.event.EventRequest
import no.fintlabs.adapter.models.v2.event.EventResponse
import no.fintlabs.adapter.models.v2.event.EventStatus
import no.fintlabs.adapter.operation.OperationType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant

class EventMappingTest {
    @Test
    fun `a v1 request turns into a v2 request and back without losing anything`() {
        val v1 =
            RequestFintEvent().apply {
                corrId = "corr-1"
                orgId = "fintlabs.no"
                domainName = "utdanning"
                packageName = "elev"
                resourceName = "elev"
                operationType = OperationType.VALIDATE
                created = 1_000
                timeToLive = 2_000
                value = "{}"
            }

        val v2 = v1.toEventRequest()

        assertThat(v2.operation).isEqualTo(EventOperation.VALIDATE)
        assertThat(v2.deadline).isEqualTo(2_000)
        assertThat(v2.toRequestFintEvent()).isEqualTo(v1)
    }

    @Test
    fun `a read request has no v1 shape`() {
        val read =
            EventRequest
                .builder()
                .corrId("corr-1")
                .operation(EventOperation.READ)
                .build()

        assertThatThrownBy { read.toRequestFintEvent() }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `failed wins over rejected and conflicted, like the client status does`() {
        val v1 =
            ResponseFintEvent().apply {
                isFailed = true
                errorMessage = "Down"
                isRejected = true
                rejectReason = "Bad"
            }

        val v2 = v1.toEventResponse()

        assertThat(v2.status).isEqualTo(EventStatus.ERROR)
        assertThat(v2.message).isEqualTo("Down")
    }

    @ParameterizedTest
    @EnumSource(EventStatus::class)
    fun `each v2 status turns back into the matching v1 flag and message`(status: EventStatus) {
        val entry = SyncPageEntry.of("42", mapOf("navn" to "x"))
        val v2 =
            EventResponse
                .builder()
                .corrId("corr-1")
                .orgId("fintlabs.no")
                .status(status)
                .message("Why")
                .resources(listOf(entry))
                .build()

        val v1 = v2.toResponseFintEvent(EventOperation.UPDATE, Instant.ofEpochMilli(5_000))

        assertThat(v1.operationType).isEqualTo(OperationType.UPDATE)
        assertThat(v1.handledAt).isEqualTo(5_000)
        assertThat(v1.value).isEqualTo(entry)
        assertThat(v1.isFailed).isEqualTo(status == EventStatus.ERROR)
        assertThat(v1.isRejected).isEqualTo(status == EventStatus.REJECTED)
        assertThat(v1.isConflicted).isEqualTo(status == EventStatus.CONFLICT)
        assertThat(v1.toEventResponse().status).isEqualTo(status)
        assertThat(v1.toEventResponse().message).isEqualTo(if (status == EventStatus.SUCCEEDED) null else "Why")
    }
}
