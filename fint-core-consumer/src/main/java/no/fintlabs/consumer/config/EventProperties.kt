package no.fintlabs.consumer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "fint.consumer.event")
data class EventProperties(
    /** How long an adapter has to answer an event before it is failed as expired. */
    val answerDeadline: Duration = Duration.ofMinutes(15),
    /** How long the event document lives in Mongo before the TTL monitor purges it. */
    val retention: Duration = Duration.ofMinutes(30),
)
