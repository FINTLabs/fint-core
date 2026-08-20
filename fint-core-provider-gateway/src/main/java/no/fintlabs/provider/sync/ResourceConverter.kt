package no.fintlabs.provider.sync

import no.novari.core.shared.json.FintJson
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.model.toResourceClass
import no.novari.fint.core.model.FintResource
import org.springframework.stereotype.Service

@Service
class ResourceConverter {
    private val objectMapper = FintJson.storageMapper()

    fun convert(
        coords: ResourceCoordinate,
        json: String,
    ): FintResource = objectMapper.readValue(json, coords.toResourceClass())
}
