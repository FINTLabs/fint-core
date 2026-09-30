package no.fintlabs.adapter.gateway.config

import no.fintlabs.adapter.gateway.relation.ResourceSelection
import no.novari.fint.core.model.FintModel
import no.novari.fint.core.model.FintResourceRef
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

/**
 * Reads the `scope` of an admin job: `all` for every type the org has stored, a component such as
 * `utdanning/elev` for every type in it, or one resource such as `utdanning/elev/person`.
 */
@Component
class ResourceSelectionConverter : Converter<String, ResourceSelection> {
    override fun convert(source: String): ResourceSelection =
        when {
            source == ALL -> ResourceSelection.AllStored
            source.count { it == '/' } == 1 -> ResourceSelection.Types(typesIn(source))
            else -> ResourceSelection.Types(listOf(typeOf(source)))
        }

    private fun typesIn(component: String): List<FintResourceRef> {
        val (domainName, packageName) = component.split("/")
        return FintModel
            .refsIn(domainName, packageName)
            .sortedBy { it.resourceName }
            .ifEmpty { throw IllegalArgumentException("Not a component the model serves: $component") }
    }

    private fun typeOf(resource: String): FintResourceRef =
        FintModel.refOf(resource) ?: throw IllegalArgumentException("Not a resource the model serves: $resource")

    companion object {
        const val ALL = "all"
    }
}
