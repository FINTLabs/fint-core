package no.fintlabs.client.config

import no.novari.core.shared.model.OrgId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class ConsumerConfigurationTest {
    @Test
    fun `the configured org id is read as an org id`() {
        val configuration = ConsumerConfiguration(baseUrl = "https://testorg.no", orgIdValue = "foo.org")

        assertEquals(OrgId.from("foo.org"), configuration.orgId)
    }

    @Test
    fun `a base url with capital letters is refused`() {
        assertThrows<IllegalArgumentException> {
            ConsumerConfiguration(baseUrl = "https://TestOrg.no", orgIdValue = "foo.org")
        }
    }
}
