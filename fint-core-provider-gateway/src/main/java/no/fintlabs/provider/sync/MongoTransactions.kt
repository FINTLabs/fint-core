package no.fintlabs.provider.sync

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

    fun <T> run(block: () -> T): T {
        if (TransactionSynchronizationManager.hasResource(mongoDatabaseFactory)) return block()

        var attempts = 0
        while (true) {
            try {
                return mongoTransactionTemplate.execute { block() }!!
            } catch (exception: RuntimeException) {
                attempts++
                if (attempts >= MAX_RETRY_ATTEMPTS || !exception.isTransientTransactionError()) throw exception
                log.warn("Retrying Mongo transaction after transient error (attempt{})", attempts, exception)
            }
        }
    }

    private fun Throwable.isTransientTransactionError(): Boolean =
        generateSequence(this) { it.cause }.any {
            (it as? MongoException)?.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL) == true
        }

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 5
    }
}
