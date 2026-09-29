package no.fintlabs.adapter.gateway.config

import no.fintlabs.adapter.gateway.admin.FintComponent
import no.novari.fint.core.model.FintModel
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class FintComponentConverter : Converter<String, FintComponent> {
    override fun convert(source: String): FintComponent {
        val parts = source.split("/")
        require(parts.size == 2) { "Not a component, expected domain/package: $source" }
        val (domainName, packageName) = parts
        val resources = FintModel.refsIn(domainName, packageName).sortedBy { it.resourceName }
        require(resources.isNotEmpty()) { "Not a component the model serves: $source" }
        return FintComponent(domainName, packageName, resources)
    }
}
