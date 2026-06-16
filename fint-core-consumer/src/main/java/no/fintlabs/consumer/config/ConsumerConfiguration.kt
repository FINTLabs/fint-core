package no.fintlabs.consumer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.Name
import java.time.Duration

// TODO: Split up the configuration to be more modular, there's too many unrelated configurations in one location
@ConfigurationProperties(prefix = "fint.consumer")
data class ConsumerConfiguration(
    val baseUrl: String,
    @param:Name("org-id")
    private val orgIdValue: String,
    val domain: String,
    val packageName: String,
    val podUrl: String,
    val autorelation: AutorelationConfig = AutorelationConfig(),
    val coreVersionHeader: String = "2",
    val kafka: KafkaConfiguration = KafkaConfiguration(),
) {
    init {
        require(baseUrl == baseUrl.lowercase()) { "baseUrl must be lowercase: $baseUrl" }
    }

    val orgId: OrgId
        get() = OrgId.from(orgIdValue)

    val componentUrl: String
        get() = "$baseUrl/$domain/$packageName"

    fun matchesComponent(
        domainName: String,
        packageName: String,
    ): Boolean =
        this.domain.equals(domainName, ignoreCase = true) &&
            this.packageName.equals(packageName, ignoreCase = true)

    fun matchesConfiguration(
        domainName: String,
        packageName: String,
        orgId: String,
    ): Boolean =
        matchesComponent(domainName, packageName) &&
            this.orgId.matches(orgId)
}

// TODO: Cleanup configuration
data class KafkaConfiguration(
    // Entity consumption in EntityConsumer & AutoRelationEntityConsumer
    val consumeLegacyResourceTopics: Boolean = false,
    val entityConcurrency: Int = 1,
    val relationEntitySeekToBeginning: Boolean = false,
    val fetchMinBytes: Int = 65536,
    val fetchMaxWaitMs: Int = 500,
    val idleBetweenPolls: Long = 0,
    val relationConcurrency: Int = 1,
    // RequestFintEvent
    val requestConcurrency: Int = 1,
    // ResponseFintEvent
    val responseConcurrency: Int = 1,
    // When true, create the consumer's default topics on startup (use for local dev + tests)
    val bootstrapTopics: Boolean = false,
    // Upper bound of uniformly-random per-pod delay before Kafka listeners start consuming.
    // Spreads initial fetch/replay load across many simultaneously-starting services.
    // Set to Duration.ZERO (e.g. in local/test configs) to start listeners immediately.
    val startupJitter: Duration = Duration.ofMinutes(3),
)

data class AutorelationConfig(
    val enabled: Boolean = true,
    val buffer: BufferConfig = BufferConfig(),
) {
    data class BufferConfig(
        /** Duration to retain unresolved relation links before eviction. Default: 30 days. */
        val ttl: Duration = Duration.ofDays(30),
    )
}
