package no.fintlabs.consumer.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ConsumerConfigurationTest {
    private val consumerConfiguration =
        ConsumerConfiguration(
            baseUrl = "https://testorg.no",
            orgIdValue = "foo.org",
            podUrl = "http://consumer.test",
        )

    @Test
    fun `resolves orgId from the configured value`() {
        assertEquals(OrgId.from("foo.org"), consumerConfiguration.orgId)
    }
}
