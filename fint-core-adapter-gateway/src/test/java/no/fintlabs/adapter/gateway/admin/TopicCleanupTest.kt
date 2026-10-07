package no.fintlabs.adapter.gateway.admin

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.adapter.gateway.config.TopicCleanupProperties
import no.fintlabs.adapter.gateway.kafka.topic.KafkaTopicService
import no.novari.core.shared.kafka.EventTopics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TopicCleanupTest {
    private val topics: KafkaTopicService = mockk()
    private val bufferTopic = "novari-no.fint-core.fint-felleskomponent-resource"
    private val existing =
        setOf(
            "afk-no.fint-core.entity-utdanning-elev-elev",
            "afk-no.fint-core.entity-utdanning-elev-person",
            "afk-no.fint-core.relation-update-utdanning-elev",
            "afk-no.fint-core.fint-felleskomponent-resource",
            "novari-no.fint-core.fint-felleskomponent-adapter-heartbeat",
            "someone-elses.topic.entity",
            "__consumer_offsets",
            bufferTopic,
            EventTopics.requestTopic(),
            EventTopics.responseTopic(),
        )

    init {
        every { topics.listTopicNames() } returns existing
        every { topics.deleteTopics(any()) } answers { firstArg<Collection<String>>().associateWith { null } }
    }

    private fun cleanup(properties: TopicCleanupProperties = TopicCleanupProperties()) = TopicCleanup(topics, properties, bufferTopic)

    @Test
    fun `a dry run reports what would go and deletes nothing`() {
        val report = cleanup().cleanup(Regex(".*entity.*"), dryRun = true, requestedBy = "tester")

        assertThat(report.dryRun).isTrue()
        assertThat(report.deletable).containsExactly(
            "afk-no.fint-core.entity-utdanning-elev-elev",
            "afk-no.fint-core.entity-utdanning-elev-person",
        )
        assertThat(report.deleted).isEmpty()
        verify(exactly = 0) { topics.deleteTopics(any()) }
    }

    @Test
    fun `a real run deletes the topics a dry run reported`() {
        val dry = cleanup().cleanup(Regex(".*entity.*"), dryRun = true, requestedBy = "tester")
        val real = cleanup().cleanup(Regex(".*entity.*"), dryRun = false, requestedBy = "tester")

        assertThat(real.deleted).isEqualTo(dry.deletable)
        verify { topics.deleteTopics(real.deleted) }
    }

    @Test
    fun `a topic without fint-core in its name is never deleted`() {
        val report = cleanup().cleanup(Regex(".*"), dryRun = false, requestedBy = "tester")

        assertThat(report.deleted).doesNotContain("someone-elses.topic.entity", "__consumer_offsets")
        assertThat(report.skipped)
            .contains(SkippedTopic("someone-elses.topic.entity", "Name does not contain fint-core"))
    }

    @Test
    fun `the topics the gateway uses are never deleted, even by a pattern that matches everything`() {
        val report = cleanup().cleanup(Regex(".*"), dryRun = false, requestedBy = "tester")

        assertThat(report.deleted).containsExactlyInAnyOrder(
            "afk-no.fint-core.entity-utdanning-elev-elev",
            "afk-no.fint-core.entity-utdanning-elev-person",
            "afk-no.fint-core.relation-update-utdanning-elev",
        )
        assertThat(report.skipped.map { it.topic }).contains(
            bufferTopic,
            EventTopics.requestTopic(),
            EventTopics.responseTopic(),
            "afk-no.fint-core.fint-felleskomponent-resource",
            "novari-no.fint-core.fint-felleskomponent-adapter-heartbeat",
        )
    }

    @Test
    fun `the default deny patterns cover every topic name the gateway builds`() {
        val patterns = TopicCleanupProperties().denyPatterns.map { Regex(it) }

        listOf(bufferTopic, EventTopics.requestTopic(), EventTopics.responseTopic()).forEach { name ->
            assertThat(patterns.any { it.matches(name) }).describedAs(name).isTrue()
        }
    }

    @Test
    fun `the pattern has to match the whole topic name`() {
        val report = cleanup().cleanup(Regex("entity"), dryRun = true, requestedBy = "tester")

        assertThat(report.matched).isEmpty()
    }

    @Test
    fun `topics are deleted in batches of the configured size`() {
        val many = (1..7).map { "afk-no.fint-core.entity-$it" }.toSet()
        every { topics.listTopicNames() } returns many

        cleanup(TopicCleanupProperties(batchSize = 3)).cleanup(Regex(".*"), dryRun = false, requestedBy = "tester")

        verify(exactly = 1) { topics.deleteTopics(many.sorted().subList(0, 3)) }
        verify(exactly = 1) { topics.deleteTopics(many.sorted().subList(3, 6)) }
        verify(exactly = 1) { topics.deleteTopics(many.sorted().subList(6, 7)) }
    }

    @Test
    fun `a topic Kafka refuses to delete is reported as failed and the rest still go`() {
        every { topics.deleteTopics(any()) } answers {
            firstArg<Collection<String>>().associateWith { name ->
                if (name.endsWith("person")) IllegalStateException("not authorized") else null
            }
        }

        val report = cleanup().cleanup(Regex(".*entity.*"), dryRun = false, requestedBy = "tester")

        assertThat(report.deleted).containsExactly("afk-no.fint-core.entity-utdanning-elev-elev")
        assertThat(report.failed)
            .containsExactly(FailedTopic("afk-no.fint-core.entity-utdanning-elev-person", "not authorized"))
    }
}
