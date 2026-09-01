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

/**
 * The lifecycle of an event. Every transition is a conditional update guarded on the current
 * state, so exactly one transition out of [PENDING] ever wins: an adapter's answer moves it to
 * [ANSWERED], the expiry sweeper moves it to [EXPIRED], and whichever update reaches the
 * document second matches nothing.
 */
enum class EventState {
    PENDING,
    ANSWERED,
    EXPIRED,
}

/**
 * One client write request and, once answered, its response, stored as a single document.
 * The payloads are kept as JSON strings in [request] and [response]: what the client sent and
 * the adapter answered is served back exactly as it arrived, and storing it unchanged avoids
 * mapping unknown resource payloads through the Mongo converter. The queryable fields live
 * top-level.
 * An expired event stores no response; [status] alone says it died.
 *
 * [deadline] is when the event must be answered by; [expireAt] is when Mongo's TTL monitor
 * may purge the whole document. TTL deletion is garbage collection only and lags up to a
 * minute or more, so serving and status logic always filter on [status] and [deadline], never
 * on the document's continued existence.
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
)

/**
 * The stored event as the services see it, with the payloads parsed back from their stored
 * JSON strings.
 */
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

/**
 * The org's event collection, `<orgId>_events`. One collection per org, mirroring
 * `<orgId>_relation_edges`, so the org lives in the collection name.
 */
fun OrgId.toEventCollectionName(): String = value.replace(".", "_") + EVENT_COLLECTION_SUFFIX

fun ResourceCoordinate.toEventCollectionName(): String = OrgId.from(orgId).toEventCollectionName()
