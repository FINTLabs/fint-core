package no.fintlabs.provider.sync

import com.fasterxml.jackson.databind.ObjectMapper
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.fint.model.resource.FintResource
import no.novari.metamodel.MetamodelService
import org.springframework.stereotype.Service

@Service
class ResourceConverter(
    private val objectMapper: ObjectMapper,
    private val metaModelService: MetamodelService,
) {
    /**
     * Converts POJO to FintResource
     */
    fun convert(
        coords: ResourceCoordinate,
        resource: Any,
    ): FintResource =
        with(coords) {
            val resourceClass =
                metaModelService
                    .getResource(domainName, packageName, resourceName)
                    ?.resourceClass
                    ?: throw RuntimeException() // TODO create custom exception
            when (resource) {
                is String -> objectMapper.readValue(resource, resourceClass)
                else -> objectMapper.convertValue(resource, resourceClass)
            }
        }
}
