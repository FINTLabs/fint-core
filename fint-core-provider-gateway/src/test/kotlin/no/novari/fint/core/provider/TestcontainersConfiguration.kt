package no.novari.fint.core.provider

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.containers.PostgreSQLContainer

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:18.3")

    @Bean
    @ServiceConnection
    fun mongoDbContainer(): MongoDBContainer =
        MongoDBContainer("mongo:7.0")
}
