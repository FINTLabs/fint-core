package no.novari.core.shared.event

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.v2.event.EventOperation
import no.fintlabs.adapter.models.v2.event.EventRequest
import no.fintlabs.adapter.models.v2.event.EventResponse
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.ResourceCoordinate
import org.springframework.data.annotation.Id
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

private val mapper =
    JsonMapper
        .builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

enum class EventState {
    PENDING,
    ANSWERED,
    EXPIRED,
}

/**
 * One event as stored in Mongo. [request] and [response] are JSON in the v2 shape
 * ([EventRequest], [EventResponse]) when [format] is [CURRENT_FORMAT]. Documents written before
 * the v2 shape have no [format] and hold the v1 shape ([RequestFintEvent], [ResponseFintEvent]);
 * they are read through the v1 to v2 mapping. Events live for 30 minutes, so the old shape is
 * gone from Mongo half an hour after the change is deployed.
 */
data class EventDocument(
    @Id val corrId: String,
    val status: EventState,
    val orgId: String,
    val domainName: String,
    val packageName: String,
    val resourceName: String,
    val created: Instant,
    val deadline: Instant,
    val expireAt: Instant,
    val request: String,
    val response: String? = null,
    val handledAt: Instant? = null,
    val format: Int? = null,
    val operation: EventOperation? = null,
)

data class StoredEvent(
    val status: EventState,
    val request: EventRequest,
    val response: EventResponse?,
    val deadline: Instant,
    val handledAt: Instant? = null,
)

const val CURRENT_FORMAT = 2

fun EventRequest.toEventDocument(expireAt: Instant): EventDocument =
    EventDocument(
        corrId = corrId,
        status = EventState.PENDING,
        orgId = orgId,
        domainName = domainName,
        packageName = packageName,
        resourceName = resourceName,
        created = Instant.ofEpochMilli(created),
        deadline = Instant.ofEpochMilli(deadline),
        expireAt = expireAt,
        request = toStoredJson(),
        format = CURRENT_FORMAT,
        operation = operation,
    )

fun EventRequest.toStoredJson(): String = mapper.writeValueAsString(this)

fun EventResponse.toStoredJson(): String = mapper.writeValueAsString(this)

fun EventDocument.toStoredEvent(): StoredEvent =
    StoredEvent(
        status = status,
        request = parseRequest(),
        response = parseResponse(),
        deadline = deadline,
        handledAt = handledAt,
    )

fun EventDocument.parseRequest(): EventRequest =
    if (format == CURRENT_FORMAT) {
        mapper.readValue(request, EventRequest::class.java)
    } else {
        mapper.readValue(request, RequestFintEvent::class.java).toEventRequest()
    }

private fun EventDocument.parseResponse(): EventResponse? =
    response?.let {
        if (format == CURRENT_FORMAT) {
            mapper.readValue(it, EventResponse::class.java)
        } else {
            mapper.readValue(it, ResponseFintEvent::class.java).toEventResponse()
        }
    }

const val EVENT_COLLECTION_SUFFIX = "_events"

fun OrgId.toEventCollectionName(): String = value.replace(".", "_") + EVENT_COLLECTION_SUFFIX

fun ResourceCoordinate.toEventCollectionName(): String = OrgId.from(orgId).toEventCollectionName()
