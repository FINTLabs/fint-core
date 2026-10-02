package no.fintlabs.client.config

import no.novari.core.shared.model.OrgId
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.Name

@ConfigurationProperties(prefix = "fint.consumer")
data class ConsumerConfiguration(
    val baseUrl: String,
    @param:Name("org-id")
    private val orgIdValue: String,
    val autorelation: AutorelationConfig = AutorelationConfig(),
    val paging: PagingProperties = PagingProperties(),
) {
    init {
        require(baseUrl == baseUrl.lowercase()) { "baseUrl must be lowercase: $baseUrl" }
    }

    val orgId: OrgId
        get() = OrgId.from(orgIdValue)
}

data class PagingProperties(
    val cursorKey: String? = null,
)

data class AutorelationConfig(
    val enabled: Boolean = true,
)
