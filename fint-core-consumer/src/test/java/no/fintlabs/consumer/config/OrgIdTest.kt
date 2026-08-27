package no.fintlabs.consumer.config

import no.novari.core.shared.model.OrgId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class OrgIdTest {
    @Test
    fun `from normalizes separators and casing`() {
        val expected = OrgId("foo.org")

        assertEquals(expected, OrgId.from("Foo_ORG"))
        assertEquals(expected, OrgId.fromTopicSegment("foo-org"))
    }

    @Test
    fun `asTopicSegment converts dots to dashes`() {
        assertEquals("foo-org", OrgId("foo.org").asTopicSegment)
    }

    @Test
    fun `constructor rejects values that are not in the internal form`() {
        assertThrows<IllegalArgumentException> { OrgId("Foo.Org") }
        assertThrows<IllegalArgumentException> { OrgId("foo-org") }
        assertThrows<IllegalArgumentException> { OrgId("foo_org") }
    }
}
