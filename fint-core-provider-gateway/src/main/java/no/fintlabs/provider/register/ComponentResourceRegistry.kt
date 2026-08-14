package no.fintlabs.provider.register

import no.novari.fint.core.model.FintModel
import org.springframework.stereotype.Component

@Component
class ComponentResourceRegistry {
    fun containsResource(
        domainName: String,
        packageName: String,
        resourceName: String,
    ): Boolean = FintModel.byPath(domainName, packageName, resourceName) != null
}
