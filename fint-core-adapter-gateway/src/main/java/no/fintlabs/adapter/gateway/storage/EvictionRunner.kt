package no.fintlabs.adapter.gateway.storage

import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs evictions in the background, one at a time, so the Kafka listener that completed a full
 * sync is not held for the minutes a large eviction takes.
 */
@Component
class EvictionRunner : DisposableBean {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor(
            Thread
                .ofPlatform()
                .name("eviction")
                .daemon(true)
                .factory(),
        )

    fun submit(eviction: () -> Unit) {
        executor.execute { eviction() }
    }

    override fun destroy() {
        executor.shutdownNow()
    }
}
