package no.fintlabs.consumer.resource

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.config.EndpointsConstants
import no.fintlabs.consumer.resource.dto.FintResourcesResponse
import no.fintlabs.consumer.resource.dto.LastUpdatedResponse
import no.fintlabs.consumer.resource.dto.ResourceCacheSizeResponse
import no.fintlabs.consumer.resource.event.RequestAccepted
import no.fintlabs.consumer.resource.event.RequestFailed
import no.fintlabs.consumer.resource.event.RequestGone
import no.fintlabs.consumer.resource.event.RequestValidated
import no.fintlabs.consumer.resource.event.ResourceCreated
import no.fintlabs.consumer.resource.event.ResourceDeleted
import no.novari.core.shared.model.OrgId
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

@RestController
@RequestMapping("{domainName}/{packageName}/{resourceName}")
class ResourceController(
    private val resourceService: ResourceService,
    private val consumerConfig: ConsumerConfiguration,
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
    ): ResponseEntity<FintResourcesResponse> =
        resourceService
            .getResources(
                ResourceCoordinate(orgId, domainName, packageName, resourceName),
                size,
                offset,
                sinceTimeStamp,
                filter,
            ).let { ResponseEntity.ok(it) }

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
    ): ResponseEntity<FintResourcesResponse> =
        getResource(domainName, packageName, resourceName, size, offset, sinceTimeStamp, filter, orgId)

    @GetMapping(EndpointsConstants.BY_ID)
    fun getResourceById(
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
        @PathVariable idField: String,
        @PathVariable idValue: String,
        @RequestHeader("x-org-id") orgId: String,
    ): ResponseEntity<FintResource?> =
        resourceService
            .getResourceById(
                ResourceCoordinate(orgId, domainName, packageName, resourceName),
                idField.lowercase(),
                idValue,
            )?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @GetMapping(EndpointsConstants.LAST_UPDATED)
    fun getLastUpdated(
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
        @RequestHeader("x-org-id") orgId: String,
    ): ResponseEntity<LastUpdatedResponse> =
        resourceService
            .getLastUpdated(ResourceCoordinate(orgId, domainName, packageName, resourceName))
            .let { ResponseEntity.ok(LastUpdatedResponse(it)) }

    @GetMapping(EndpointsConstants.CACHE_SIZE)
    fun getResourceCacheSize(
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
        @RequestHeader("x-org-id") orgId: String,
    ): ResponseEntity<ResourceCacheSizeResponse> =
        resourceService
            .getCacheSize(ResourceCoordinate(orgId, domainName, packageName, resourceName))
            .let { ResponseEntity.ok(ResourceCacheSizeResponse(it)) }

    @GetMapping(EndpointsConstants.STATUS_ID)
    fun getStatus(
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
        @PathVariable corrId: String,
        @RequestHeader("x-org-id") orgId: String,
    ): ResponseEntity<Any?> = ResponseEntity.ok().build()

    @PostMapping
    fun postResource(
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
        @RequestBody resourceData: Any,
        @RequestParam(name = "validate", required = false) validateOnly: Boolean,
        @RequestHeader("x-org-id") orgId: String,
    ): ResponseEntity<Nothing> = ResponseEntity.ok().build()
//         requestFintEventService
//             .createAndPublish(resource, resourceData, validateOnly)
//             .toAcceptedResponse()

    @PutMapping(EndpointsConstants.BY_ID)
    fun putResource(
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
        @PathVariable idField: String,
        @PathVariable idValue: String,
        @RequestBody resourceData: Any?,
        @RequestHeader("x-org-id") orgId: String,
    ): ResponseEntity<Nothing> = ResponseEntity.ok().build()
//         requestFintEventService
//             .createAndPublish(resource, resourceData, OperationType.UPDATE)
//             .toAcceptedResponse()

    private fun RequestFailed.FailureType.toHttpStatus() =
        when (this) {
            RequestFailed.FailureType.REJECTED -> HttpStatus.BAD_REQUEST
            RequestFailed.FailureType.CONFLICT -> HttpStatus.CONFLICT
            RequestFailed.FailureType.ERROR -> HttpStatus.INTERNAL_SERVER_ERROR
        }

    private fun RequestFintEvent.toLocationUri(): URI =
        URI.create("${consumerConfig.componentUrl}/$resourceName/status/$corrId")

    private fun RequestFintEvent.toAcceptedResponse(): ResponseEntity<Nothing> =
        ResponseEntity.accepted().location(toLocationUri()).build()

    companion object {
        private val logger = LoggerFactory.getLogger(ResourceController::class.java)
    }
}
