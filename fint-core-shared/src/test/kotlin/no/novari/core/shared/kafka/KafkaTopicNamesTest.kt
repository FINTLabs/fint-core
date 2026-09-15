package no.novari.core.shared.kafka

import no.novari.core.shared.model.OrgId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class KafkaTopicNamesTest {
    @Test
    fun `event topic normalizes dotted org id`() {
        assertEquals(
            "novari-no.fint-core.fint-felleskomponent-adapter-heartbeat",
            KafkaTopicNames.eventTopic("fint-felleskomponent-adapter-heartbeat"),
        )
    }
}
