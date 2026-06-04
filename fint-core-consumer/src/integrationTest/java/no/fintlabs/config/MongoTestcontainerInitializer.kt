package no.fintlabs.config

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName

/**
 * Starts a singleton [MongoDBContainer] per JVM and exposes its connection string to Spring via
 * the `spring.data.mongodb.uri` system property. Registered globally via the
 * `META-INF/services/org.junit.jupiter.api.extension.Extension` service file so every integration
 * test sees a fresh, container-backed Mongo without per-class boilerplate.
 *
 * Each test class also gets a clean slate via [beforeAll], which drops every `cache_*`
 * collection so leftover data from a prior test class cannot leak in.
 */
class MongoTestcontainerInitializer : BeforeAllCallback {
    override fun beforeAll(context: ExtensionContext) {
        val db = mongoClient.getDatabase(DB_NAME)
        db
            .listCollectionNames()
            .filter { it.startsWith("cache_") || it.startsWith("sync_") }
            .forEach { db.getCollection(it).drop() }
    }

    companion object {
        private const val IMAGE = "mongo:7.0"
        private const val DB_NAME = "fintcache-test"

        @JvmStatic
        val MONGO: MongoDBContainer by lazy {
            MongoDBContainer(DockerImageName.parse(IMAGE)).apply {
                start()
                System.setProperty("spring.data.mongodb.uri", getReplicaSetUrl(DB_NAME))
            }
        }

        private val mongoClient: MongoClient by lazy { MongoClients.create(MONGO.replicaSetUrl) }
    }
}
