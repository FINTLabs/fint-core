package no.fintlabs.adapter.gateway.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "fint.provider.event.long-poll")
data class EventLongPollProperties(
    // The longest an adapter may ask to wait. Stays well below the access gateway's 120-second limit.
    val maxWait: Duration = Duration.ofSeconds(90),
    // How often a held request looks for new events.
    val recheckInterval: Duration = Duration.ofMillis(500),
) {
    init {
        require(!maxWait.isNegative) { "fint.provider.event.long-poll.max-wait must not be negative" }
        require(recheckInterval.isPositive) { "fint.provider.event.long-poll.recheck-interval must be above 0" }
    }
}
