package no.fintlabs.provider.config

import no.novari.core.shared.model.OrgId
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.Name

@ConfigurationProperties(prefix = "fint.provider")
data class ProviderProperties(
    @param:Name("org-id")
    private val orgIdValue: String,
    val baseUrl: String = "https://api.felleskomponent.no",
) {
    val orgId: OrgId get() = OrgId.from(orgIdValue)
}
