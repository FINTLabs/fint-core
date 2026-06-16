package no.fintlabs.consumer.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class OrgIdTest {
    @Test
    fun `from normalizes separators and casing`() {
        assertEquals(OrgId("foo.org"), OrgId.from("Foo_ORG"))
        assertEquals(OrgId("foo.org"), OrgId.fromTopicSegment("foo-org"))
    }

    @Test
    fun `asTopicSegment converts dots to dashes`() {
        assertEquals("foo-org", OrgId("foo.org").asTopicSegment)
    }

    @Test
    fun `constructor rejects non-canonical values`() {
        assertThrows<IllegalArgumentException> { OrgId("Foo.Org") }
        assertThrows<IllegalArgumentException> { OrgId("foo-org") }
        assertThrows<IllegalArgumentException> { OrgId("foo_org") }
    }
}
