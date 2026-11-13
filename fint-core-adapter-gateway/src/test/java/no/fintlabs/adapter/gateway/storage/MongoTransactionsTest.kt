package no.fintlabs.adapter.gateway.storage

import com.mongodb.MongoCommandException
import com.mongodb.ServerAddress
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

class MongoTransactionsTest {
    @Test
    fun `runs the block again after a write conflict that carries no transient label`() {
        val block = FailingBlock(failures = 2) { writeConflict() }

        assertThat(transactions().inTransaction(block::run)).isEqualTo("stored")
        assertThat(block.runs).isEqualTo(3)
    }

    @Test
    fun `runs the block again after a failure labelled transient`() {
        val block = FailingBlock(failures = 1) { labelledTransient() }

        assertThat(transactions().inTransaction(block::run)).isEqualTo("stored")
        assertThat(block.runs).isEqualTo(2)
    }

    @Test
    fun `finds the write conflict under the exception Spring wraps it in`() {
        val block = FailingBlock(failures = 1) { TransientDataAccessResourceException("wrapped", writeConflict()) }

        assertThat(transactions().inTransaction(block::run)).isEqualTo("stored")
        assertThat(block.runs).isEqualTo(2)
    }

    @Test
    fun `does not run the block again after a failure that will not pass`() {
        val block = FailingBlock(failures = 1) { duplicateKey() }

        val thrown = assertThrows<MongoCommandException> { transactions().inTransaction(block::run) }

        assertThat(thrown.errorCode).isEqualTo(DUPLICATE_KEY)
        assertThat(block.runs).isEqualTo(1)
    }

    @Test
    fun `keeps trying for as long as there is time left`() {
        val block = FailingBlock(failures = 5) { writeConflict() }

        assertThat(transactions().inTransaction(block::run)).isEqualTo("stored")
        assertThat(block.runs).isEqualTo(6)
    }

    @Test
    fun `gives up when the time is used and rethrows the write conflict`() {
        val block = FailingBlock(failures = Int.MAX_VALUE) { writeConflict() }

        val thrown =
            assertThrows<MongoCommandException> {
                transactions(retryFor = Duration.ofMillis(300)).inTransaction(block::run)
            }

        assertThat(thrown.errorCode).isEqualTo(WRITE_CONFLICT)
        assertThat(block.runs).isGreaterThan(1)
    }

    private fun transactions(retryFor: Duration = Duration.ofSeconds(10)) =
        MongoTransactions(TransactionTemplate(NoTransactions), mockk(), retryFor)

    /** Throws what [failure] makes the first [failures] times it runs, then returns. */
    private class FailingBlock(
        private val failures: Int,
        private val failure: () -> RuntimeException,
    ) {
        var runs = 0
            private set

        fun run(): String {
            runs++
            if (runs <= failures) throw failure()
            return "stored"
        }
    }

    /** Lets the template run its callback without a database behind it. */
    private object NoTransactions : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

        override fun commit(status: TransactionStatus) = Unit

        override fun rollback(status: TransactionStatus) = Unit
    }

    private companion object {
        const val WRITE_CONFLICT = 112
        const val DUPLICATE_KEY = 11000

        /** The answer Azure DocumentDB gives when a write meets another transaction. It has no error labels. */
        fun writeConflict() = commandFailure(WRITE_CONFLICT, "WriteConflict", "Could not acquire lock within transaction")

        fun labelledTransient() = commandFailure(251, "NoSuchTransaction", "Transaction was aborted", "TransientTransactionError")

        fun duplicateKey() = commandFailure(DUPLICATE_KEY, "DuplicateKey", "E11000 duplicate key error")

        fun commandFailure(
            code: Int,
            codeName: String,
            message: String,
            vararg labels: String,
        ): MongoCommandException {
            val response =
                BsonDocument()
                    .append("ok", BsonInt32(0))
                    .append("errmsg", BsonString(message))
                    .append("code", BsonInt32(code))
                    .append("codeName", BsonString(codeName))
            if (labels.isNotEmpty()) response.append("errorLabels", BsonArray(labels.map(::BsonString)))

            return MongoCommandException(response, ServerAddress())
        }
    }
}
