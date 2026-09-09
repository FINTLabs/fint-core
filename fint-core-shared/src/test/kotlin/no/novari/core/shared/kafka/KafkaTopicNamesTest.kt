package no.novari.core.shared.kafka

import no.novari.core.shared.model.OrgId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class KafkaTopicNamesTest {
    @Test
    fun `event topic normalizes dotted org id`() {
        assertEquals(
            "fintlabs-no.fint-core.fint-felleskomponent-adapter-heartbeat",
            KafkaTopicNames.eventTopic(OrgId.from("fintlabs.no"), "fint-felleskomponent-adapter-heartbeat"),
        )
    }

    @Test
    fun `entity topic accepts an existing topic segment`() {
        assertEquals(
            "afk-no.fint-core.entity.utdanning-elev",
            KafkaTopicNames.entityTopic("afk-no", "utdanning-elev"),
        )
    }
}
