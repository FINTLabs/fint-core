package no.fintlabs.provider.sync

import com.fasterxml.jackson.databind.ObjectMapper
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.model.toResourceClass
import no.novari.fint.core.model.FintResource
import org.springframework.stereotype.Service

@Service
class ResourceConverter(
    private val objectMapper: ObjectMapper,
) {
    fun convert(
        coords: ResourceCoordinate,
        json: String,
    ): FintResource = objectMapper.readValue(json, coords.toResourceClass())
}
