package no.fintlabs.adapter.gateway.event.request

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class EventSignalTest {
    private val signal = EventSignal()

    @Test
    fun `a signal wakes a subscription on that collection`() {
        signal.subscribe(listOf("fintlabs_no_events")).use { subscription ->
            val woken = CompletableFuture.runAsync { subscription.await(LONG) }

            signal.signal("fintlabs_no_events")

            woken.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `a signal that came before the wait is not lost`() {
        signal.subscribe(listOf("fintlabs_no_events")).use { subscription ->
            signal.signal("fintlabs_no_events")

            assertThat(elapsed { subscription.await(LONG) }).isLessThan(Duration.ofSeconds(5))
        }
    }

    @Test
    fun `a signal for another collection does not wake it`() {
        signal.subscribe(listOf("fintlabs_no_events")).use { subscription ->
            signal.signal("vtfk_no_events")

            assertThat(elapsed { subscription.await(SHORT) }).isGreaterThanOrEqualTo(SHORT)
        }
    }

    @Test
    fun `a closed subscription is not signalled`() {
        val subscription = signal.subscribe(listOf("fintlabs_no_events"))
        subscription.close()

        signal.signal("fintlabs_no_events")

        assertThat(elapsed { subscription.await(SHORT) }).isGreaterThanOrEqualTo(SHORT)
    }

    @Test
    fun `a signal to all wakes the subscriptions on every collection`() {
        signal.subscribe(listOf("fintlabs_no_events")).use { first ->
            signal.subscribe(listOf("vtfk_no_events")).use { second ->
                signal.signalAll()

                assertThat(elapsed { first.await(LONG) }).isLessThan(Duration.ofSeconds(5))
                assertThat(elapsed { second.await(LONG) }).isLessThan(Duration.ofSeconds(5))
            }
        }
    }

    @Test
    fun `a stop wakes every subscription and ends later waits at once`() {
        signal.subscribe(listOf("fintlabs_no_events")).use { subscription ->
            val woken = CompletableFuture.runAsync { subscription.await(LONG) }

            signal.stop()

            woken.get(5, TimeUnit.SECONDS)
            assertThat(signal.isRunning).isFalse
            assertThat(elapsed { subscription.await(LONG) }).isLessThan(Duration.ofSeconds(5))
        }
    }

    private fun elapsed(block: () -> Unit): Duration {
        val start = System.nanoTime()
        block()
        return Duration.ofNanos(System.nanoTime() - start)
    }

    private companion object {
        val SHORT: Duration = Duration.ofMillis(100)
        val LONG: Duration = Duration.ofMinutes(1)
    }
}
