package no.fintlabs.provider.sync

import com.fasterxml.jackson.databind.ObjectMapper
import lombok.RequiredArgsConstructor
import lombok.extern.slf4j.Slf4j
import no.novari.fint.model.resource.FintResource
import no.novari.metamodel.MetamodelService
import org.springframework.stereotype.Service

@Service
@RequiredArgsConstructor
@Slf4j
class ResourceConverter(
    private val objectMapper: ObjectMapper,
    private val metaModelService: MetamodelService,
) {
    /**
     * Converts POJO to FintResource
     */
    fun convert(
        domainName: String,
        packageName: String,
        resourceName: String,
        resource: Any,
    ): FintResource =
        metaModelService.getResource(domainName, packageName, resourceName)?.let {
            objectMapper.convertValue(resource, it.resourceClass)
        } ?: throw RuntimeException() // TODO create custom exception
}
