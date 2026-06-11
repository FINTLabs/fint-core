package no.novari.fint.core.provider.datasync.ingest

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.containers.PostgreSQLContainer

@TestConfiguration(proxyBeanMethods = false)
class BenchmarkContainersConfiguration {
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer<*> = PostgreSQLContainer("postgres:18.3")

    @Bean
    @ServiceConnection
    fun mongoDbContainer(): MongoDBContainer =
        MongoDBContainer("mongo:7.0").withCreateContainerCmdModifier { cmd ->
            cmd.hostConfig?.withNanoCPUs(MONGO_CPUS * 1_000_000_000L)
        }

    companion object {
        val MONGO_CPUS: Long = java.lang.Long.getLong("benchmark.mongoCpus", 2L)
    }
}
