package no.fintlabs.adapter.gateway.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration

class SweepIntervalPropertiesTest {
    @Test
    fun `the event expiry sweep runs every 30 seconds unless configured`() {
        assertThat(bind<EventProperties>("fint.provider.event").expirySweepInterval).isEqualTo(Duration.ofSeconds(30))
    }

    @Test
    fun `the event expiry sweep interval is read from configuration`() {
        val properties = bind<EventProperties>("fint.provider.event", "fint.provider.event.expiry-sweep-interval" to "PT5S")

        assertThat(properties.expirySweepInterval).isEqualTo(Duration.ofSeconds(5))
    }

    @Test
    fun `the resource TTL sweep runs every hour unless configured`() {
        assertThat(bind<ResourceTtlProperties>("fint.provider.resource-ttl").sweepInterval).isEqualTo(Duration.ofHours(1))
    }

    @Test
    fun `the resource TTL sweep interval is read from configuration`() {
        val properties = bind<ResourceTtlProperties>("fint.provider.resource-ttl", "fint.provider.resource-ttl.sweep-interval" to "PT10M")

        assertThat(properties.sweepInterval).isEqualTo(Duration.ofMinutes(10))
    }

    private inline fun <reified T : Any> bind(
        prefix: String,
        vararg values: Pair<String, String>,
    ): T =
        Binder(MapConfigurationPropertySource(mapOf(*values)))
            .bindOrCreate(prefix, T::class.java)
}
