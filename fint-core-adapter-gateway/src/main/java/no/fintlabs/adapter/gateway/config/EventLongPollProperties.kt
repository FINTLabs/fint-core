package no.fintlabs.adapter.gateway.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "fint.provider.event.long-poll")
data class EventLongPollProperties(
    /**
     * How long a request is held when it asks to wait and gives no number of seconds. It stays
     * below the 120 seconds after which the access gateway in front cuts a request.
     */
    val defaultWait: Duration = Duration.ofSeconds(90),
    /** The longest time one request is held. A longer wait in the request is cut to this. */
    val maxWait: Duration = Duration.ofMinutes(15),
)
