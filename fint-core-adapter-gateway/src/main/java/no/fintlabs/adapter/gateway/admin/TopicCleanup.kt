package no.fintlabs.adapter.gateway.admin

import no.fintlabs.adapter.gateway.config.TopicCleanupProperties
import no.fintlabs.adapter.gateway.kafka.topic.KafkaTopicService
import no.fintlabs.adapter.gateway.kafka.topic.TopicNamesConstants
import no.novari.core.shared.kafka.EventTopics
import no.novari.core.shared.kafka.KafkaTopicNames
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/**
 * Deletes Kafka topics whose full name matches a regex. A topic is only ever deleted when its name
 * contains `fint-core` and it is not one the gateway uses today. The topics in use are the event
 * request and response topics, the five adapter topics and this gateway's own buffer topic, taken
 * from the same code that creates them, plus every name matching one of the configured deny
 * patterns. The event and adapter topics exist once, under `novari-no`, so only that exact name is
 * protected. Only topics that exist once per org are covered by a pattern.
 *
 * A dry run goes through the same steps and reports the same result, but deletes nothing. Real
 * deletes go in batches, and each batch is logged.
 */
@Service
class TopicCleanup(
    private val topics: KafkaTopicService,
    private val properties: TopicCleanupProperties,
    @param:Qualifier("topicBufferName") private val bufferTopic: String,
) {
    private val denyPatterns = properties.denyPatterns.map { Regex(it) }
    private val protectedNames =
        setOf(
            EventTopics.requestTopic(),
            EventTopics.responseTopic(),
            bufferTopic,
            KafkaTopicNames.eventTopic(TopicNamesConstants.ADAPTER_HEARTBEAT),
            KafkaTopicNames.eventTopic(TopicNamesConstants.ADAPTER_CONTRACT),
            KafkaTopicNames.eventTopic(TopicNamesConstants.ADAPTER_FULL_SYNC),
            KafkaTopicNames.eventTopic(TopicNamesConstants.ADAPTER_DELTA_SYNC),
            KafkaTopicNames.eventTopic(TopicNamesConstants.ADAPTER_DELETE_SYNC),
        )

    fun cleanup(
        pattern: Regex,
        dryRun: Boolean,
        requestedBy: String,
    ): TopicCleanupReport {
        val matched = topics.listTopicNames().filter { pattern.matches(it) }.sorted()
        val skipped = matched.mapNotNull { name -> skipReason(name)?.let { SkippedTopic(name, it) } }
        val skippedNames = skipped.map { it.topic }.toSet()
        val deletable = matched.filterNot { it in skippedNames }

        logger.info(
            "Topic cleanup by {} with pattern '{}' (dryRun={}): {} matched, {} to delete, {} skipped",
            requestedBy,
            pattern.pattern,
            dryRun,
            matched.size,
            deletable.size,
            skipped.size,
        )
        if (dryRun) {
            return TopicCleanupReport(pattern.pattern, true, matched, deletable, emptyList(), skipped, emptyList())
        }

        val deleted = mutableListOf<String>()
        val failed = mutableListOf<FailedTopic>()

        deletable.chunked(properties.batchSize).forEachIndexed { index, batch ->
            val results = topics.deleteTopics(batch)
            batch.forEach { name ->
                val error = results[name]
                if (error == null) {
                    deleted += name
                } else {
                    failed +=
                        FailedTopic(
                            name,
                            error.message ?: error.javaClass.simpleName,
                        )
                }
            }
            logger.info(
                "Topic cleanup by {}, batch {}: {} deleted, {} failed: {}",
                requestedBy,
                index + 1,
                batch.count { results[it] == null },
                batch.count { results[it] != null },
                batch,
            )
        }
        return TopicCleanupReport(pattern.pattern, false, matched, deletable, deleted, skipped, failed)
    }

    private fun skipReason(name: String): String? =
        when {
            REQUIRED_TEXT !in name -> "Name does not contain $REQUIRED_TEXT"
            name in protectedNames -> "Topic is in use by the gateway"
            denyPatterns.any { it.matches(name) } -> "Name matches a deny pattern"
            else -> null
        }

    private companion object {
        const val REQUIRED_TEXT = "fint-core"
        val logger: Logger = LoggerFactory.getLogger(TopicCleanup::class.java)
    }
}

data class TopicCleanupReport(
    val pattern: String,
    val dryRun: Boolean,
    val matched: List<String>,
    val deletable: List<String>,
    val deleted: List<String>,
    val skipped: List<SkippedTopic>,
    val failed: List<FailedTopic>,
)

data class SkippedTopic(
    val topic: String,
    val reason: String,
)

data class FailedTopic(
    val topic: String,
    val error: String,
)
