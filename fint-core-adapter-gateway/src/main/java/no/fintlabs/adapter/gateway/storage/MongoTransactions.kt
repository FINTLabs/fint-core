package no.fintlabs.adapter.gateway.storage

import com.mongodb.MongoException
import org.slf4j.LoggerFactory
import org.springframework.core.retry.RetryListener
import org.springframework.core.retry.RetryPolicy
import org.springframework.core.retry.RetryState
import org.springframework.core.retry.RetryTemplate
import org.springframework.core.retry.Retryable
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.function.Supplier

@Component
class MongoTransactions(
    private val mongoTransactionTemplate: TransactionTemplate,
    private val mongoDatabaseFactory: MongoDatabaseFactory,
    private val retryFor: Duration = RETRY_FOR,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val retry =
        RetryTemplate(
            RetryPolicy
                .builder()
                // Azure DocumentDB fails a write that meets another transaction at once, where
                // Mongo makes it wait. The wait has to happen here, so the time decides when to
                // give up and not the number of tries.
                .maxRetries(Long.MAX_VALUE)
                .timeout(retryFor)
                .delay(Duration.ofMillis(50))
                .jitter(Duration.ofMillis(50))
                .multiplier(2.0)
                .maxDelay(Duration.ofMillis(500))
                .predicate { it.passesWhenRunAgain() }
                .build(),
        ).apply { retryListener = RetryLogger() }

    /**
     * Runs a block inside one Mongo transaction. When it fails in a way that passes if the
     * transaction runs again, for example a write conflict with another writer, the whole block
     * runs again from the start, for as long as [retryFor]. Each wait before a retry is twice the
     * one before, up to half a second. Any other failure, or running out of time, rethrows the
     * original exception and rolls back. Spring reports rollback and errors by throwing, so the
     * template only hands back null when the block does.
     */
    fun <T> inTransaction(block: () -> T): T {
        if (TransactionSynchronizationManager.hasResource(mongoDatabaseFactory)) return block()

        return retry.invoke(Supplier { mongoTransactionTemplate.execute { block() }!! })
    }

    /**
     * Walks the cause chain (this, cause, cause of cause, and so on) and returns true if any
     * exception in it is a [MongoException] that passes when the transaction runs again. Needed
     * because Spring wraps the driver's exception, so the failure is rarely the outermost one.
     */
    private fun Throwable.passesWhenRunAgain(): Boolean =
        generateSequence(this) { it.cause }.filterIsInstance<MongoException>().any {
            // Azure DocumentDB reports a write conflict without the transient label, so the code counts too.
            it.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL) || it.code == WRITE_CONFLICT
        }

    private inner class RetryLogger : RetryListener {
        override fun beforeRetry(
            retryPolicy: RetryPolicy,
            retryable: Retryable<*>,
            retryState: RetryState,
        ) {
            // A writer that waits for another one retries many times, so only the first retry is logged.
            if (retryState.retryCount > 1) return

            log.warn("Retrying Mongo transaction for up to {} after an error that can pass", retryFor, retryState.lastException)
        }
    }

    companion object {
        /**
         * Longer than a full batch from another writer takes, and far below the five minutes
         * Kafka gives a consumer between two polls.
         */
        val RETRY_FOR: Duration = Duration.ofSeconds(30)
        private const val WRITE_CONFLICT = 112
    }
}
