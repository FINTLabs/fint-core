package no.fintlabs.adapter.gateway.relation

import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.util.concurrent.Executors

/**
 * The one background thread relation edge jobs run on. It is not an `Executor` bean on purpose:
 * Spring Boot only makes its own task executor when no `Executor` bean exists.
 */
@Component
class RelationEdgeJobRunner {
    private val executor =
        Executors.newSingleThreadExecutor { task ->
            Thread
                .ofPlatform()
                .name("relation-edge-job")
                .daemon()
                .unstarted(task)
        }

    fun submit(task: () -> Unit) {
        executor.execute(task)
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }
}
