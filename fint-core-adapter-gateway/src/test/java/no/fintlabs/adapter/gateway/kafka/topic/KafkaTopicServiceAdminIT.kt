package no.fintlabs.adapter.gateway.kafka.topic

import no.fintlabs.adapter.gateway.ProviderAppIT
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KafkaTopicServiceAdminIT : ProviderAppIT() {
    @Autowired
    private lateinit var service: KafkaTopicService

    @Test
    fun `a topic that is deleted is gone from the list`() {
        service.createOrModifyEventTopic("afk-no.fint-core.admin-delete-it", 1, Duration.ofDays(1))
        assertTrue("afk-no.fint-core.admin-delete-it" in service.listTopicNames())

        val result = service.deleteTopics(listOf("afk-no.fint-core.admin-delete-it"))

        assertEquals(mapOf("afk-no.fint-core.admin-delete-it" to null), result)
        assertFalse("afk-no.fint-core.admin-delete-it" in service.listTopicNames())
    }

    @Test
    fun `a topic that does not exist is reported as failed`() {
        val result = service.deleteTopics(listOf("afk-no.fint-core.never-existed"))

        assertTrue(result.getValue("afk-no.fint-core.never-existed") != null)
    }
}
