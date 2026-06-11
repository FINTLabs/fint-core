package no.novari.fint.core.shared.kafka

import org.springframework.boot.autoconfigure.kafka.KafkaProperties

/**
 * Replicates the SSL client properties the fint-kafka library assembled from Boot's
 * `spring.kafka.ssl.*` when `fint.kafka.enable-ssl` is set — how every Kafka client
 * in this platform reaches Aiven.
 */
fun kafkaSecurityProperties(
    kafkaProperties: KafkaProperties,
    enableSsl: Boolean,
): Map<String, Any> {
    if (!enableSsl) return emptyMap()
    val ssl = kafkaProperties.ssl
    return mapOf(
        "security.protocol" to ssl.protocol,
        "ssl.truststore.location" to ssl.trustStoreLocation.file.absolutePath,
        "ssl.truststore.password" to ssl.trustStorePassword,
        "ssl.keystore.type" to ssl.keyStoreType,
        "ssl.keystore.location" to ssl.keyStoreLocation.file.absolutePath,
        "ssl.keystore.password" to ssl.keyStorePassword,
        "ssl.key.password" to ssl.keyPassword,
    )
}
