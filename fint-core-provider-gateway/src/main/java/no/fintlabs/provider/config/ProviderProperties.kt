package no.fintlabs.provider.config

import no.fintlabs.consumer.links.LinkConfiguration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "fint.provider")
data class ProviderProperties(
    override val baseUrl: String = "https://api.felleskomponent.no",
    val components: List<ComponentConfig> = emptyList()
) : LinkConfiguration

data class ComponentConfig(
    val domainName: String = "",
    val packageName: String = "",
    val orgIds: List<String> = emptyList(),
    val relationUpdate: Boolean = false,
    val requestPartitions: Int? = null,
    val responsePartitions: Int? = null
)
