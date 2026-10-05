package no.fintlabs.adapter.gateway.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/**
 * Settings for events. [maxReadAnswerSize] caps how large one answer to a read event may be as
 * JSON. Read results are stored on the event itself, and a Mongo document holds at most 16 MB
 * including the request, so the default leaves room for the rest of the document.
 *
 * [expirySweepInterval] is how long the sweeper waits after one run before the next, when it
 * marks events past their deadline as expired.
 */
@ConfigurationProperties(prefix = "fint.provider.event")
data class EventProperties(
    val maxReadAnswerSize: DataSize = DataSize.ofMegabytes(8),
    val expirySweepInterval: Duration = Duration.ofSeconds(30),
)
