package no.fintlabs.adapter.gateway.config

import no.fintlabs.adapter.gateway.relation.ResourceSelection
import no.novari.fint.core.model.FintModel
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

/**
 * Reads the `scope` of an admin job: `all`, a component such as `utdanning/elev`, or one
 * resource such as `utdanning/elev/person`. Refuses a component or resource the model does not
 * serve.
 */
@Component
class ResourceSelectionConverter : Converter<String, ResourceSelection> {
    override fun convert(source: String): ResourceSelection =
        when {
            source == ALL -> ResourceSelection.All
            source.count { it == '/' } == 1 -> component(source)
            else -> resource(source)
        }

    private fun component(source: String): ResourceSelection.Component {
        val (domainName, packageName) = source.split("/")
        require(FintModel.refsIn(domainName, packageName).isNotEmpty()) { "Not a component the model serves: $source" }
        return ResourceSelection.Component(domainName, packageName)
    }

    private fun resource(source: String): ResourceSelection.Resource =
        ResourceSelection.Resource(
            FintModel.refOf(source) ?: throw IllegalArgumentException("Not a resource the model serves: $source"),
        )

    companion object {
        const val ALL = "all"
    }
}
