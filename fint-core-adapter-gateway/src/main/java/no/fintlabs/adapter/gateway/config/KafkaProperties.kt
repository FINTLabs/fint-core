package no.fintlabs.adapter.gateway.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "fint.provider.kafka")
data class KafkaProperties(
    val replicas: Int = 2,
    val adapter: AdapterKafkaProperties = AdapterKafkaProperties(),
)

data class AdapterKafkaProperties(
    val partitions: Int = 1,
    val heartbeatRetentionTime: Duration = Duration.ofDays(1),
    val fullSyncRetentionTime: Duration = Duration.ofDays(1),
    val deltaSyncRetentionTime: Duration = Duration.ofDays(1),
    val deleteSyncRetentionTime: Duration = Duration.ofDays(1),
)
