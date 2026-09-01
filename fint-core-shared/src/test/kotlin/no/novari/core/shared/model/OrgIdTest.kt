package no.novari.core.shared.model

import no.novari.core.shared.event.toEventCollectionName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrgIdTest {
    private val primary = OrgId.from("novari.no")

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
