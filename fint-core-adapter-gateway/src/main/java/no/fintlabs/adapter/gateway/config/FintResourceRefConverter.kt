package no.fintlabs.adapter.gateway.config

import no.novari.fint.core.model.FintModel
import no.novari.fint.core.model.FintResourceRef
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class FintResourceRefConverter : Converter<String, FintResourceRef> {
    override fun convert(source: String): FintResourceRef = FintModel.refOf(source) ?: throw IllegalArgumentException(reasonFor(source))

    private fun reasonFor(source: String): String {
        val parts = source.split("/")
        return if (parts.size == 2 && FintModel.refsIn(parts[0], parts[1]).isNotEmpty()) {
            "$source is a component, send it as component=$source"
        } else {
            "Not a resource the model serves: $source"
        }
    }
}
