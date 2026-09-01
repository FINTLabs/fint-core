package no.fintlabs.provider.storage

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * The transaction manager is deliberately not a bean: registering a second TransactionManager
 * next to the JPA one would make every unqualified transaction ambiguous. Only the template is
 * exposed, bound to Mongo, for the code that needs an all-or-nothing write.
 */
@Configuration
class MongoTransactionConfig {
    @Bean
    fun mongoTransactionTemplate(mongoDatabaseFactory: MongoDatabaseFactory): TransactionTemplate =
        TransactionTemplate(MongoTransactionManager(mongoDatabaseFactory))
}
