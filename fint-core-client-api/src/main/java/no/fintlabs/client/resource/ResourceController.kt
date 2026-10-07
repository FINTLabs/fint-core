package no.fintlabs.client.resource

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.operation.OperationType
import no.fintlabs.client.admin.StatsService
import no.fintlabs.client.config.ConsumerConfiguration
import no.fintlabs.client.config.EndpointsConstants
import no.fintlabs.client.resource.dto.FintResourcesResponse
import no.fintlabs.client.resource.dto.LastUpdatedResponse
import no.fintlabs.client.resource.dto.ResourceCacheSizeResponse
import no.fintlabs.client.resource.event.LiveReadService
import no.fintlabs.client.resource.event.PreferHeader
import no.fintlabs.client.resource.event.RequestAccepted
import no.fintlabs.client.resource.event.RequestFailed
import no.fintlabs.client.resource.event.RequestFintEventService
import no.fintlabs.client.resource.event.RequestGone
import no.fintlabs.client.resource.event.RequestStatusService
import no.fintlabs.client.resource.event.RequestValidated
import no.fintlabs.client.resource.event.ResourceCreated
import no.fintlabs.client.resource.event.ResourceDeleted
import no.fintlabs.client.resource.event.ResourceNotRead
import no.fintlabs.client.resource.event.ResourceRead
import no.fintlabs.client.resource.event.ResourcesRead
import no.fintlabs.client.resource.paging.PageCursor
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.fint.core.model.FintResource
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * A read answers from the cache unless the client prefers an asynchronous answer and the read
 * can go live. Then the read is sent to the adapter as an event, and the client gets `202`
 * with the status location and `Preference-Applied: respond-async`. The status endpoint serves
 * the result once the adapter has answered.
 */
@RestController
@RequestMapping("{domainName}/{packageName}/{resourceName}")
class ResourceController(
    private val resourceService: ResourceService,
    private val requestFintEventService: RequestFintEventService,
    private val requestStatusService: RequestStatusService,
    private val liveReadService: LiveReadService,
    private val consumerConfig: ConsumerConfiguration,
    private val statsService: StatsService,
) {
    @GetMapping
    fun getResource(
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
        @RequestParam(defaultValue = "0") size: Int,
        @RequestParam(defaultValue = "0") offset: Long,
        @RequestParam(defaultValue = "0") sinceTimeStamp: Long,
        @RequestParam(required = false, name = "\$filter") filter: String?,
        @RequestHeader("x-org-id") orgId: String,
        @RequestParam(required = false) cursor: PageCursor?,
        @RequestHeader(name = PreferHeader.NAME, required = false) prefer: String?,
    ): ResponseEntity<FintResourcesResponse> {
        val coordinate = ResourceCoordinate(orgId, domainName, packageName, resourceName)

        liveReadService
            .startByFilter(coordinate, filter, ListOptions(size, offset, sinceTimeStamp, cursor), prefer)
            ?.let { return it.toLiveReadAccepted(domainName, packageName) }

        return resourceService
            .getResources(coordinate, size, offset, sinceTimeStamp, filter, cursor)
            .let { ResponseEntity.ok(it) }
    }

    @PostMapping("/\$query")
    fun getResourceByOdataFilter(
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
        @RequestParam(defaultValue = "0") size: Int,
        @RequestParam(defaultValue = "0") offset: Long,
        @RequestParam(defaultValue = "0") sinceTimeStamp: Long,
        @RequestBody(required = false) filter: String?,
        @RequestHeader("x-org-id") orgId: String,
        @RequestParam(required = false) cursor: PageCursor?,
        @RequestHeader(name = PreferHeader.NAME, required = false) prefer: String?,
    ): ResponseEntity<FintResourcesResponse> =
        getResource(
            domainName,
            packageName,
            resourceName,
            size,
            offset,
            sinceTimeStamp,
            filter,
            orgId,
            cursor,
            prefer,
        )

    @GetMapping(EndpointsConstants.BY_ID)
    fun getResourceById(
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
        @PathVariable idField: String,
        @PathVariable idValue: String,
        @RequestHeader("x-org-id") orgId: String,
        @RequestHeader(name = PreferHeader.NAME, required = false) prefer: String?,
    ): ResponseEntity<FintResource> {
        val coordinate = ResourceCoordinate(orgId, domainName, packageName, resourceName)

        liveReadService
            .startById(coordinate, idField, idValue, prefer)
            ?.let { return it.toLiveReadAccepted(domainName, packageName) }

        return resourceService
            .getResourceById(coordinate, idField.lowercase(), idValue)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    }

    @GetMapping(EndpointsConstants.LAST_UPDATED)
    fun getLastUpdated(
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
        @RequestHeader("x-org-id") orgId: String,
    ): ResponseEntity<LastUpdatedResponse> =
        statsService
            .getLastUpdated(ResourceCoordinate(orgId, domainName, packageName, resourceName))
            .let { ResponseEntity.ok(LastUpdatedResponse(it)) }

    @GetMapping(EndpointsConstants.CACHE_SIZE)
    fun getResourceCacheSize(
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
        @RequestHeader("x-org-id") orgId: String,
    ): ResponseEntity<ResourceCacheSizeResponse> =
        statsService
            .getCacheSize(ResourceCoordinate(orgId, domainName, packageName, resourceName))
            .let { ResponseEntity.ok(ResourceCacheSizeResponse(it)) }

    @GetMapping(EndpointsConstants.STATUS_ID)
    fun getStatus(
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
        @PathVariable corrId: String,
        @RequestHeader("x-org-id") orgId: String,
    ): ResponseEntity<Any> =
        requestStatusService
            .getStatusResponse(ResourceCoordinate(orgId, domainName, packageName, resourceName), corrId)
            .let { result ->
                logger.debug("Status of Event: {} returned: {}", corrId, result)
                when (result) {
                    is ResourceCreated -> ResponseEntity.created(result.location).body(result.body)
                    is RequestValidated -> ResponseEntity.ok(result.body)
                    is ResourcesRead -> ResponseEntity.ok(result.body)
                    is ResourceRead -> ResponseEntity.ok(result.body)
                    is ResourceNotRead -> ResponseEntity.notFound().build()
                    is ResourceDeleted -> ResponseEntity.noContent().build()
                    is RequestAccepted -> ResponseEntity.accepted().build()
                    is RequestGone -> ResponseEntity.status(HttpStatus.GONE).build()
                    is RequestFailed -> ResponseEntity.status(result.failureType.toHttpStatus()).body(result.body)
                }
            }

    @PostMapping
    fun postResource(
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
        @RequestBody resourceData: Any,
        @RequestParam(name = "validate", required = false) validateOnly: Boolean,
        @RequestHeader("x-org-id") orgId: String,
    ): ResponseEntity<Nothing> =
        requestFintEventService
            .createAndPublish(
                ResourceCoordinate(orgId, domainName, packageName, resourceName),
                resourceData,
                validateOnly,
            ).toAcceptedResponse(domainName, packageName)

    @PutMapping(EndpointsConstants.BY_ID)
    fun putResource(
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
        @PathVariable idField: String,
        @PathVariable idValue: String,
        @RequestBody resourceData: Any?,
        @RequestHeader("x-org-id") orgId: String,
    ): ResponseEntity<Nothing> =
        requestFintEventService
            .createAndPublish(
                ResourceCoordinate(orgId, domainName, packageName, resourceName),
                resourceData,
                OperationType.UPDATE,
            ).toAcceptedResponse(domainName, packageName)

    private fun RequestFailed.FailureType.toHttpStatus() =
        when (this) {
            RequestFailed.FailureType.REJECTED -> HttpStatus.BAD_REQUEST
            RequestFailed.FailureType.CONFLICT -> HttpStatus.CONFLICT
            RequestFailed.FailureType.ERROR -> HttpStatus.INTERNAL_SERVER_ERROR
        }

    private fun RequestFintEvent.toAcceptedResponse(
        domainName: String,
        packageName: String,
    ): ResponseEntity<Nothing> =
        ResponseEntity
            .accepted()
            .location(statusLocation(domainName, packageName))
            .build()

    private fun <T : Any> RequestFintEvent.toLiveReadAccepted(
        domainName: String,
        packageName: String,
    ): ResponseEntity<T> =
        ResponseEntity
            .accepted()
            .header(PreferHeader.APPLIED, PreferHeader.RESPOND_ASYNC)
            .location(statusLocation(domainName, packageName))
            .build<T>()

    private fun RequestFintEvent.statusLocation(
        domainName: String,
        packageName: String,
    ): URI = URI.create("${consumerConfig.baseUrl}/$domainName/$packageName/$resourceName/status/$corrId".lowercase())

    companion object {
        private val logger = LoggerFactory.getLogger(ResourceController::class.java)
    }
}
