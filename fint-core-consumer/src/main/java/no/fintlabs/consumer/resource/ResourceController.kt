package no.fintlabs.consumer.resource

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.operation.OperationType
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.config.EndpointsConstants
import no.fintlabs.consumer.kafka.event.RequestFintEventService
import no.fintlabs.consumer.resource.aspect.IdFieldCheck
import no.fintlabs.consumer.resource.aspect.WriteableResource
import no.fintlabs.consumer.resource.dto.LastUpdatedResponse
import no.fintlabs.consumer.resource.dto.ResourceCacheSizeResponse
import no.fintlabs.consumer.resource.event.RequestAccepted
import no.fintlabs.consumer.resource.event.RequestFailed
import no.fintlabs.consumer.resource.event.RequestGone
import no.fintlabs.consumer.resource.event.RequestStatusService
import no.fintlabs.consumer.resource.event.RequestValidated
import no.fintlabs.consumer.resource.event.ResourceCreated
import no.fintlabs.consumer.resource.event.ResourceDeleted
import no.fintlabs.model.resource.FintResources
import no.novari.fint.model.resource.FintResource
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/{resource}")
class ResourceController(
    private val resourceService: ResourceService,
    private val requestFintEventService: RequestFintEventService,
    private val requestStatusService: RequestStatusService,
    private val consumerConfig: ConsumerConfiguration,
) {
    @GetMapping
    fun getResource(
        @PathVariable resource: String,
        @RequestParam(defaultValue = "0") size: Int,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "0") sinceTimeStamp: Long,
        @RequestParam(required = false, name = "\$filter") filter: String?,
    ): FintResources? =
        resourceService.getResources(
            resource,
            size,
            offset,
            sinceTimeStamp,
            filter,
        )

    @PostMapping("/\$query")
    fun getResourceByOdataFilter(
        @PathVariable resource: String,
        @RequestParam(defaultValue = "0") size: Int,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "0") sinceTimeStamp: Long,
        @RequestBody(required = false) filter: String?,
    ): FintResources? = getResource(resource, size, offset, sinceTimeStamp, filter)

    @IdFieldCheck
    @GetMapping(EndpointsConstants.BY_ID)
    fun getResourceById(
        @PathVariable resource: String,
        @PathVariable idField: String,
        @PathVariable idValue: String,
    ): ResponseEntity<FintResource?> =
        resourceService
            .getResourceById(resource, idField, idValue)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @GetMapping(EndpointsConstants.LAST_UPDATED)
    fun getLastUpdated(
        @PathVariable resource: String,
    ): ResponseEntity<LastUpdatedResponse> =
        resourceService.getLastUpdated(resource).let {
            ResponseEntity.ok(LastUpdatedResponse(it))
        }

    @GetMapping(EndpointsConstants.CACHE_SIZE)
    fun getResourceCacheSize(
        @PathVariable resource: String,
    ): ResponseEntity<ResourceCacheSizeResponse> =
        resourceService.getCacheSize(resource).let {
            ResponseEntity.ok(ResourceCacheSizeResponse(it))
        }

    @WriteableResource
    @GetMapping(EndpointsConstants.STATUS_ID)
    fun getStatus(
        @PathVariable resource: String,
        @PathVariable corrId: String,
    ): ResponseEntity<Any?> =
        requestStatusService.getStatusResponse(resource, corrId).let { result ->
            logger.debug("Status of Event: {} returned: {}", corrId, result)
            return when (result) {
                is ResourceCreated -> ResponseEntity.created(result.location).body(result.body)
                is RequestValidated -> ResponseEntity.ok(result.body)
                is ResourceDeleted -> ResponseEntity.noContent().build()
                is RequestAccepted -> ResponseEntity.accepted().build()
                is RequestGone -> ResponseEntity.status(HttpStatus.GONE).build()
                is RequestFailed -> ResponseEntity.status(result.failureType.toHttpStatus()).body(result.body)
            }
        }

    @WriteableResource
    @PostMapping
    fun postResource(
        @PathVariable resource: String,
        @RequestBody resourceData: Any,
        @RequestParam(name = "validate", required = false) validateOnly: Boolean,
    ): ResponseEntity<Nothing> =
        requestFintEventService
            .createAndPublish(resource, resourceData, validateOnly)
            .toAcceptedResponse()

    @IdFieldCheck
    @WriteableResource
    @PutMapping(EndpointsConstants.BY_ID)
    fun putResource(
        @PathVariable resource: String,
        @PathVariable idField: String,
        @PathVariable idValue: String,
        @RequestBody resourceData: Any?,
    ): ResponseEntity<Nothing> =
        requestFintEventService
            .createAndPublish(resource, resourceData, OperationType.UPDATE)
            .toAcceptedResponse()

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
