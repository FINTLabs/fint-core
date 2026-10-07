package no.fintlabs.adapter.gateway.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "fint.provider.admin.topic-cleanup")
data class TopicCleanupProperties(
    val batchSize: Int = 50,
    val denyPatterns: List<String> =
        listOf("[a-z0-9-]+\\.fint-core\\.fint-felleskomponent-resource"),
) {
    init {
        require(batchSize > 0) { "fint.provider.admin.topic-cleanup.batch-size must be above 0" }
    }
}
