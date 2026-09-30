package no.fintlabs.adapter.gateway.config

import no.novari.fint.core.model.FintModel
import no.novari.fint.core.model.FintResourceRef
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class FintResourceRefConverter : Converter<String, FintResourceRef> {
    override fun convert(source: String): FintResourceRef =
        FintModel.refOf(source) ?: throw IllegalArgumentException("Not a resource the model serves: $source")
}
