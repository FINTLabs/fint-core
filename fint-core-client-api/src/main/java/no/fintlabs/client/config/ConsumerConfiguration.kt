package no.fintlabs.client.config

import no.novari.core.shared.model.OrgId
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.Name
import java.time.Duration

@ConfigurationProperties(prefix = "fint.consumer")
data class ConsumerConfiguration(
    val baseUrl: String,
    @param:Name("org-id")
    private val orgIdValue: String,
    val autorelation: AutorelationConfig = AutorelationConfig(),
    val paging: PagingProperties = PagingProperties(),
    val adapterGateway: AdapterGatewayProperties = AdapterGatewayProperties(),
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

/** The org's adapter gateway, which client-api asks what the adapters can read live. Both run in the org's namespace. */
data class AdapterGatewayProperties(
    val url: String = "http://fint-core-adapter-gateway:8080",
    val timeout: Duration = Duration.ofSeconds(2),
)
