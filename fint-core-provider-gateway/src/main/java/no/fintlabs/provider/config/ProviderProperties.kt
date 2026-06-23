package no.fintlabs.provider.config

import no.novari.core.shared.model.OrgId
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.Name

@ConfigurationProperties(prefix = "fint.provider")
data class ProviderProperties(
    @param:Name("org-id")
    private val orgIdValue: String,
    val components: List<ComponentConfig> = emptyList(),
) {
    val orgId: OrgId get() = OrgId.from(orgIdValue)
}

data class ComponentConfig(
    val domainName: String = "",
    val packageName: String = "",
    val orgIds: List<String> = emptyList(),
    val relationUpdate: Boolean = false,
    val requestPartitions: Int? = null,
    val responsePartitions: Int? = null,
)
