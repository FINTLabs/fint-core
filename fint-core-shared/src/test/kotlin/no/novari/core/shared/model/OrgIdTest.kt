package no.novari.core.shared.model

import no.novari.core.shared.event.toEventCollectionName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OrgIdTest {
    private val primary = OrgId.from("novari.no")

    @Test
    fun `an org id is written with dots and in lowercase`() {
        assertEquals("foo.org", OrgId.from("foo.org").value)
        assertEquals("foo.org", OrgId.from("Foo_ORG").value)
        assertEquals("foo.org", OrgId.from("foo-org").value)
        assertEquals("foo.org", OrgId.from(" foo.org ").value)
    }

    @Test
    fun `an org id read from a topic segment is written with dots`() {
        assertEquals("foo.org", OrgId.fromTopicSegment("foo-org").value)
    }

    @Test
    fun `a blank org id is refused`() {
        assertThrows<IllegalArgumentException> { OrgId.from("") }
        assertThrows<IllegalArgumentException> { OrgId.from("  ") }
    }

    @Test
    fun `two spellings of the same org are the same org id`() {
        val dotted = OrgId.from("foo.org")
        val dashed = OrgId.from("FOO-org")

        assertEquals(dotted, dashed)
        assertEquals(dotted.hashCode(), dashed.hashCode())
        assertEquals(1, setOf(dotted, dashed).size)
    }

    @Test
    fun `two different orgs are not the same org id`() {
        assertNotEquals(OrgId.from("foo.org"), OrgId.from("bar.org"))
    }

    @Test
    fun `an org id matches any spelling of itself`() {
        assertTrue(OrgId.from("foo.org").matches("FOO_org"))
        assertFalse(OrgId.from("foo.org").matches("bar.org"))
    }

    @Test
    fun `the topic segment of an org id is written with dashes`() {
        assertEquals("foo-org", OrgId.from("foo.org").asTopicSegment)
    }

    @Test
    fun `an org belongs to itself`() {
        assertTrue(primary.belongsTo(primary))
    }

    @Test
    fun `a sub-org belongs to its organization`() {
        assertTrue(OrgId.from("test.novari.no").belongsTo(primary))
        assertTrue(OrgId.from("dev.test.novari.no").belongsTo(primary))
    }

    @Test
    fun `a foreign org does not belong`() {
        assertFalse(OrgId.from("fintlabs.no").belongsTo(primary))
        assertFalse(OrgId.from("test.fintlabs.no").belongsTo(primary))
    }

    @Test
    fun `a shared name ending is not a sub-org`() {
        assertFalse(OrgId.from("fintlabs.no").belongsTo(OrgId.from("labs.no")))
    }

    @Test
    fun `the event collection name is the org with an events suffix`() {
        assertEquals("test_novari_no_events", OrgId.from("test.novari.no").toEventCollectionName())
    }
}
