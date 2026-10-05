package no.fintlabs.adapter.gateway.event.v2

import no.fintlabs.adapter.gateway.event.EventAnswering
import no.fintlabs.adapter.gateway.event.NoRequestFoundException
import no.fintlabs.adapter.gateway.event.response.ResponseFintEventProducer
import no.fintlabs.adapter.gateway.security.EventAuthorization
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.v2.event.EventOperation
import no.fintlabs.adapter.models.v2.event.EventResponse
import no.fintlabs.adapter.models.v2.event.EventStatus
import no.novari.core.shared.event.ClaimOutcome
import no.novari.core.shared.event.EventState
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.toEventCollectionName
import no.novari.core.shared.event.toResponseFintEvent
import no.novari.core.shared.model.OrgId
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * Handles answers from v2 adapters. The answer body has already passed Bean Validation. The
 * checks here run in this order: the event must exist and still be pending (404 otherwise, as in
 * v1), the adapter must hold the component role and list the resource and operation in its event
 * capabilities (403), and the answer must match the request (400). The answer is then stored
 * through [EventAnswering].
 *
 * A write answer is saved to the cache the same way as in v1: CREATE or UPDATE that SUCCEEDED,
 * and UPDATE with a CONFLICT, which carries the source system's version. Read results are only
 * stored on the event and never go to the cache. Read answers also stay off the Kafka feed,
 * because they are lists of live personal data and the feed keeps records for 24 hours.
 */
@Service
class EventAnswerService(
    private val eventStore: EventStore,
    private val eventAnswering: EventAnswering,
    private val eventAnswerRules: EventAnswerRules,
    private val eventAuthorization: EventAuthorization,
    private val responseFintEventProducer: ResponseFintEventProducer,
    private val clock: Clock,
) {
    fun answer(response: EventResponse) {
        val corrId = response.corrId
        val collectionName = OrgId.from(response.orgId).toEventCollectionName()

        val now = clock.instant()
        val stored = eventStore.findByCorrId(corrId, collectionName) ?: throw NoRequestFoundException(corrId)
        if (stored.status != EventState.PENDING || !now.isBefore(stored.deadline)) throw NoRequestFoundException(corrId)

        eventAuthorization.requireRoleFor(stored.request)
        eventAuthorization.requireEventCapability(stored.request)
        eventAnswerRules.validate(stored.request, response)

        val operation = stored.request.operation
        val outcome = eventAnswering.claim(stored, collectionName, response, now, resourceToSave(operation, response))
        if (outcome != ClaimOutcome.Claimed) throw NoRequestFoundException(corrId)

        if (operation != EventOperation.READ) {
            responseFintEventProducer.publish(response.toResponseFintEvent(operation, now))
        }
    }

    private fun resourceToSave(
        operation: EventOperation?,
        response: EventResponse,
    ): SyncPageEntry? =
        when {
            operation in WRITES && response.status == EventStatus.SUCCEEDED -> response.resources.single()
            operation == EventOperation.UPDATE && response.status == EventStatus.CONFLICT -> response.resources.single()
            else -> null
        }

    private companion object {
        val WRITES = setOf(EventOperation.CREATE, EventOperation.UPDATE)
    }
}
