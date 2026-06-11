package no.novari.fint.core.provider.datasync.ingest

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("fint.provider.sync-ingest")
data class SyncIngestProperties(
    val maxPollRecords: Int = 500,
    val idleBetweenPolls: Duration = Duration.ZERO,
    val partitions: Int = 1,
    val topicRetention: Duration = Duration.ofHours(24),
    val dltRetention: Duration = Duration.ofDays(7),
    val sendTimeout: Duration = Duration.ofSeconds(60),
)
