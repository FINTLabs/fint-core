package no.fintlabs.consumer.config

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

@TestConfiguration
class ClockConfiguration {
    @Bean
    fun clock(): Clock {
        val fixedDateTime = LocalDateTime.of(2020, 5, 24, 14, 0, 0)
        val zone = ZoneId.systemDefault()

        return Clock.fixed(ZonedDateTime.of(fixedDateTime, zone).toInstant(), zone)
    }
}
