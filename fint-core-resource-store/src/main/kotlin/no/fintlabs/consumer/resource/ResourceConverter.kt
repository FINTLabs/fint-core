package no.fintlabs.consumer.resource

import com.fasterxml.jackson.databind.ObjectMapper
import lombok.RequiredArgsConstructor
import lombok.extern.slf4j.Slf4j
import no.fintlabs.consumer.links.LinkService
import no.fintlabs.consumer.resource.context.ResourceContext
import no.novari.fint.model.resource.FintResource
import org.springframework.stereotype.Service

@Service
@RequiredArgsConstructor
@Slf4j
class ResourceConverter(
    private val objectMapper: ObjectMapper,
    private val resourceContext: ResourceContext,
    private val linkService: LinkService,
) {
    fun convert(
        resourceName: String,
        resource: Any,
    ): FintResource = objectMapper.convertValue(resource, resourceContext.getResource(resourceName).clazz)

    fun convertAndMapLinks(
        resourceName: String,
        resource: Any,
    ): FintResource =
        convert(resourceName, resource)
            .also { linkService.mapLinks(resourceName, it) }
}
