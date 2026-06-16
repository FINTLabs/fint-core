package no.fintlabs.consumer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "caffeine.cache")
data class CaffeineCacheProperties(
    val expireAfterAccess: Duration = Duration.ofMinutes(15),
)
