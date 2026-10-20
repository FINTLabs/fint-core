package no.fintlabs.adapter.gateway.event.request

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.concurrent.thread

class EventArrivalsTest {
    private val arrivals = EventArrivals()

    @Test
    fun `a wait returns at once when a signal came after the version was read`() {
        val seen = arrivals.version
        arrivals.signal()

        val elapsed = timed { arrivals.awaitAfter(seen, Duration.ofSeconds(5)) }

        assertThat(elapsed).isLessThan(Duration.ofMillis(500))
    }

    @Test
    fun `a wait ends when another thread signals`() {
        val seen = arrivals.version
        val signaller =
            thread {
                Thread.sleep(200)
                arrivals.signal()
            }

        val elapsed = timed { arrivals.awaitAfter(seen, Duration.ofSeconds(5)) }
        signaller.join()

        assertThat(elapsed).isBetween(Duration.ofMillis(150), Duration.ofSeconds(2))
    }

    @Test
    fun `a wait without a signal lasts its timeout`() {
        val elapsed = timed { arrivals.awaitAfter(arrivals.version, Duration.ofMillis(200)) }

        assertThat(elapsed).isBetween(Duration.ofMillis(190), Duration.ofSeconds(2))
    }

    private fun timed(block: () -> Unit): Duration {
        val start = System.nanoTime()
        block()
        return Duration.ofNanos(System.nanoTime() - start)
    }
}
