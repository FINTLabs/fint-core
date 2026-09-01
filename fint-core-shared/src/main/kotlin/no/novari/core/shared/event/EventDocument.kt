package no.novari.core.shared.event

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.ResourceCoordinate
import org.springframework.data.annotation.Id
import java.time.Instant

private val mapper = ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

enum class EventState {
    PENDING,
    ANSWERED,
    EXPIRED,
}

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
)

data class StoredEvent(
    val status: EventState,
    val request: RequestFintEvent,
    val response: ResponseFintEvent?,
    val deadline: Instant,
)

fun RequestFintEvent.toEventDocument(expireAt: Instant): EventDocument =
    EventDocument(
        corrId = corrId,
        status = EventState.PENDING,
        orgId = orgId,
        domainName = domainName,
        packageName = packageName,
        resourceName = resourceName,
        created = Instant.ofEpochMilli(created),
        deadline = Instant.ofEpochMilli(timeToLive),
        expireAt = expireAt,
        request = mapper.writeValueAsString(this),
    )

fun ResponseFintEvent.toStoredJson(): String = mapper.writeValueAsString(this)

fun EventDocument.toStoredEvent(): StoredEvent =
    StoredEvent(
        status = status,
        request = parseRequest(),
        response = response?.let { mapper.readValue(it, ResponseFintEvent::class.java) },
        deadline = deadline,
    )

fun EventDocument.parseRequest(): RequestFintEvent = mapper.readValue(request, RequestFintEvent::class.java)

const val EVENT_COLLECTION_SUFFIX = "_events"

fun OrgId.toEventCollectionName(): String = value.replace(".", "_") + EVENT_COLLECTION_SUFFIX

fun ResourceCoordinate.toEventCollectionName(): String = OrgId.from(orgId).toEventCollectionName()
