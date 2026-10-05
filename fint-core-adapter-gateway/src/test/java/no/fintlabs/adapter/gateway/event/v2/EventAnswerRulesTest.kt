package no.fintlabs.adapter.gateway.event.v2

import no.fintlabs.adapter.gateway.config.EventProperties
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.v2.event.EventIdentifier
import no.fintlabs.adapter.models.v2.event.EventOperation
import no.fintlabs.adapter.models.v2.event.EventRequest
import no.fintlabs.adapter.models.v2.event.EventResponse
import no.fintlabs.adapter.models.v2.event.EventStatus
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.util.unit.DataSize

class EventAnswerRulesTest {
    private val rules = EventAnswerRules(EventProperties(maxReadAnswerSize = DataSize.ofKilobytes(2)))

    /**
     * One row of the table on [EventResponse]: an answer with [status] and [count] resources to
     * an event with [operation] is either [accepted] or refused.
     */
    data class Answer(
        val operation: EventOperation,
        val status: EventStatus,
        val count: Int,
        val accepted: Boolean,
    )

    @ParameterizedTest
    @MethodSource("answers")
    fun `the number of resources must match the operation and status`(answer: Answer) {
        val request = request(answer.operation)
        val response = response(answer.status, answer.count)

        if (answer.accepted) {
            assertThatCode { rules.validate(request, response) }.doesNotThrowAnyException()
        } else {
            assertThatThrownBy { rules.validate(request, response) }
                .isInstanceOf(InvalidEventAnswerException::class.java)
        }
    }

    @Test
    fun `a read that found more than maxResults is told to answer REJECTED`() {
        assertThatThrownBy { rules.validate(request(EventOperation.READ, maxResults = 2), response(EventStatus.SUCCEEDED, 3)) }
            .isInstanceOf(InvalidEventAnswerException::class.java)
            .hasMessageContaining("more than maxResults (2)")
            .hasMessageContaining("Answer REJECTED")
    }

    @Test
    fun `a read by id carries at most one resource`() {
        val byId = request(EventOperation.READ).also { it.id = EventIdentifier("systemid", "1") }

        assertThatCode { rules.validate(byId, response(EventStatus.SUCCEEDED, 1)) }.doesNotThrowAnyException()
        assertThatThrownBy { rules.validate(byId, response(EventStatus.SUCCEEDED, 2)) }
            .isInstanceOf(InvalidEventAnswerException::class.java)
    }

    @Test
    fun `a read answer larger than the size limit is told to answer REJECTED`() {
        val large = response(EventStatus.SUCCEEDED, 1).also { it.resources[0].resource = mapOf("navn" to "x".repeat(3_000)) }

        assertThatThrownBy { rules.validate(request(EventOperation.READ), large) }
            .isInstanceOf(InvalidEventAnswerException::class.java)
            .hasMessageContaining("more than the limit")
    }

    private fun request(
        operation: EventOperation,
        maxResults: Int = 10,
    ): EventRequest =
        EventRequest
            .builder()
            .corrId("corr-1")
            .orgId("fintlabs.no")
            .operation(operation)
            .maxResults(if (operation == EventOperation.READ) maxResults else null)
            .build()

    private fun response(
        status: EventStatus?,
        count: Int,
    ): EventResponse =
        EventResponse
            .builder()
            .corrId("corr-1")
            .orgId("fintlabs.no")
            .status(status)
            .resources((1..count).map { SyncPageEntry.of("$it", mapOf("navn" to "n$it")) }.toMutableList())
            .build()

    companion object {
        @JvmStatic
        fun answers(): List<Arguments> =
            listOf(
                Answer(EventOperation.READ, EventStatus.SUCCEEDED, 0, accepted = true),
                Answer(EventOperation.READ, EventStatus.SUCCEEDED, 10, accepted = true),
                Answer(EventOperation.READ, EventStatus.SUCCEEDED, 11, accepted = false),
                Answer(EventOperation.READ, EventStatus.REJECTED, 0, accepted = true),
                Answer(EventOperation.READ, EventStatus.ERROR, 1, accepted = false),
                Answer(EventOperation.READ, EventStatus.CONFLICT, 1, accepted = false),
                Answer(EventOperation.CREATE, EventStatus.SUCCEEDED, 1, accepted = true),
                Answer(EventOperation.CREATE, EventStatus.SUCCEEDED, 0, accepted = false),
                Answer(EventOperation.CREATE, EventStatus.SUCCEEDED, 2, accepted = false),
                Answer(EventOperation.UPDATE, EventStatus.CONFLICT, 1, accepted = true),
                Answer(EventOperation.UPDATE, EventStatus.REJECTED, 0, accepted = true),
                Answer(EventOperation.UPDATE, EventStatus.ERROR, 1, accepted = false),
                Answer(EventOperation.VALIDATE, EventStatus.SUCCEEDED, 0, accepted = true),
                Answer(EventOperation.VALIDATE, EventStatus.SUCCEEDED, 1, accepted = false),
                Answer(EventOperation.VALIDATE, EventStatus.CONFLICT, 1, accepted = true),
            ).map { Arguments.of(it) }
    }
}
