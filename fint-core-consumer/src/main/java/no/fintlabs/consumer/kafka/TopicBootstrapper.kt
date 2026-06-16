package no.fintlabs.consumer.kafka

import jakarta.annotation.PostConstruct
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.config.OrgId
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.config.TopicConfig
import org.apache.kafka.common.errors.TopicExistsException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ExecutionException

/**
 * Creates the default topics a consumer needs so the service can start against an empty broker
 * (local dev, integration tests). Disabled by default; enable with
 * `fint.consumer.kafka.bootstrap-topics=true`.
 */
@Component
@ConditionalOnProperty("fint.consumer.kafka.bootstrap-topics", havingValue = "true")
class TopicBootstrapper(
    private val consumerConfig: ConsumerConfiguration,
    @param:Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun createTopics() {
        val topics = buildTopics()
        logger.info("Bootstrapping {} topics on {}: {}", topics.size, bootstrapServers, topics)

        val adminProps = mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers)
        AdminClient.create(adminProps).use { admin ->
            try {
                admin.createTopics(topics.map { it.toNewTopic() }).all().get()
            } catch (e: ExecutionException) {
                if (e.cause !is TopicExistsException) throw e
            }
        }
    }

    private fun buildTopics(): List<TopicSpec> {
        val prefix = "${consumerConfig.orgId.asTopicSegment}.$DOMAIN_CONTEXT"
        val fintlabsPrefix = "${FINTLABS.asTopicSegment}.$DOMAIN_CONTEXT"
        val resource = "${consumerConfig.domain}-${consumerConfig.packageName}"
        val entity30d = { name: String -> TopicSpec(name, 6, Duration.ofDays(30), COMPACT_DELETE) }
        val event7d = { name: String -> TopicSpec(name, 1, Duration.ofDays(7), DELETE) }
        return listOf(
            entity30d("$prefix.entity.$resource"),
            entity30d("$prefix.entity.$resource-relation-update"),
            event7d("$prefix.event.$resource-request"),
            event7d("$prefix.event.$resource-response"),
            event7d("$fintlabsPrefix.event.consumer-error"),
            event7d("$fintlabsPrefix.event.sync-status"),
        )
    }

    private fun TopicSpec.toNewTopic(): NewTopic =
        NewTopic(name, partitions, REPLICAS)
            .configs(
                mapOf(
                    TopicConfig.RETENTION_MS_CONFIG to retention.toMillis().toString(),
                    TopicConfig.CLEANUP_POLICY_CONFIG to cleanupPolicy,
                ),
            )

    private data class TopicSpec(
        val name: String,
        val partitions: Int,
        val retention: Duration,
        val cleanupPolicy: String,
    )

    companion object {
        private const val DOMAIN_CONTEXT = "fint-core"
        private const val REPLICAS: Short = 1
        private const val COMPACT_DELETE = "${TopicConfig.CLEANUP_POLICY_COMPACT},${TopicConfig.CLEANUP_POLICY_DELETE}"
        private const val DELETE: String = TopicConfig.CLEANUP_POLICY_DELETE
        private val FINTLABS = OrgId.from("fintlabs.no")
    }
}
