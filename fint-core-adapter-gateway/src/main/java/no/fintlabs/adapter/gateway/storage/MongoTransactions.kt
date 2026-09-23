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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val retry =
        RetryTemplate(
            RetryPolicy
                .builder()
                .maxRetries(MAX_RETRIES)
                .delay(Duration.ofMillis(50))
                .jitter(Duration.ofMillis(50))
                .multiplier(2.0)
                .maxDelay(Duration.ofMillis(500))
                .predicate { it.isTransientTransactionError() }
                .build(),
        ).apply { retryListener = RetryLogger() }

    /**
     * Runs a block inside one Mongo transaction. When Mongo flags the failure as transient, for
     * example a write conflict with another writer, the whole block runs again from the start, up
     * to [MAX_RETRIES] more times. Each wait before a retry is twice the one before,
     * so two writers that collided do not collide again at once. Any other failure,
     * or running out of retries, rethrows the original exception and rolls back. Spring reports
     * rollback and errors by throwing, so the template only hands back null when the block does.
     */
    fun <T> inTransaction(block: () -> T): T {
        if (TransactionSynchronizationManager.hasResource(mongoDatabaseFactory)) return block()

        return retry.invoke(Supplier { mongoTransactionTemplate.execute { block() }!! })
    }

    /**
     * Walks the cause chain (this, cause, cause of cause, and so on) and returns true if any
     * exception in it is a [MongoException] labeled transient. Needed because Spring wraps the
     * driver's exception, so the label is rarely on the outermost one.
     */
    private fun Throwable.isTransientTransactionError(): Boolean =
        generateSequence(this) { it.cause }.any {
            (it as? MongoException)?.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL) == true
        }

    private inner class RetryLogger : RetryListener {
        override fun beforeRetry(
            retryPolicy: RetryPolicy,
            retryable: Retryable<*>,
            retryState: RetryState,
        ) {
            log.warn(
                "Retrying Mongo transaction after transient error (retry {})",
                retryState.retryCount,
                retryState.lastException,
            )
        }
    }

    companion object {
        private const val MAX_RETRIES = 4L
    }
}
