package no.fintlabs.adapter.gateway.config

import no.novari.core.shared.model.OrgId
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class OrgIdConverter : Converter<String, OrgId> {
    override fun convert(source: String): OrgId = OrgId.from(source)
}
