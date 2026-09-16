package no.fintlabs.provider.storage

import com.mongodb.MongoException
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

@Component
class MongoTransactions(
    private val mongoTransactionTemplate: TransactionTemplate,
    private val mongoDatabaseFactory: MongoDatabaseFactory,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Runs a block inside one Mongo transaction. When Mongo flags the failure as transient, for
     * example a write conflict with another writer, the whole block runs again from the start, up
     * to [MAX_ATTEMPTS] times in total. Any other failure, or running out of attempts, rethrows
     * and rolls back.
     */
    fun <T> inTransaction(block: () -> T): T {
        if (TransactionSynchronizationManager.hasResource(mongoDatabaseFactory)) return block()

        var attempts = 0
        while (true) {
            try {
                // execute() is a Java API that only returns null when the callback itself returns null.
                // Spring reports rollback and errors by throwing, so the result is never null here.
                return mongoTransactionTemplate.execute { block() }!!
            } catch (exception: RuntimeException) {
                attempts++
                if (attempts >= MAX_ATTEMPTS || !exception.isTransientTransactionError()) throw exception
                log.warn("Retrying Mongo transaction after transient error (attempt {})", attempts, exception)
            }
        }
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

    companion object {
        private const val MAX_ATTEMPTS = 5
    }
}
