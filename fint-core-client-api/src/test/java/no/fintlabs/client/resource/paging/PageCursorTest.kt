package no.fintlabs.client.resource.paging

import no.novari.core.shared.store.PageAnchor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.time.Instant
import kotlin.test.assertEquals

/**
 * A cursor is packed into bytes before it is encrypted. These tests check that the packing keeps
 * the direction, the timestamp and the id, and that bytes that are not a packed cursor are
 * rejected.
 */
class PageCursorTest {
    private val anchor = PageAnchor(Instant.parse("2026-09-10T10:25:41.103Z"), "fravarsregistrering-103508")

    @Test
    fun `a cursor survives packing and unpacking in both directions`() {
        PageDirection.entries.forEach { direction ->
            val cursor = PageCursor(direction, anchor)

            assertEquals(cursor, PageCursor.fromBytes(cursor.toBytes()))
        }
    }

    @Test
    fun `an id with characters outside ascii survives packing`() {
        val cursor = PageCursor(PageDirection.AFTER, PageAnchor(Instant.ofEpochMilli(20), "sander-fravær/æøå"))

        assertEquals(cursor, PageCursor.fromBytes(cursor.toBytes()))
    }

    @Test
    fun `a packed cursor is one byte of direction, eight bytes of timestamp and the id`() {
        val bytes = PageCursor(PageDirection.AFTER, PageAnchor(Instant.ofEpochMilli(20), "id")).toBytes()

        assertEquals(1 + 8 + 2, bytes.size)
    }

    @Test
    fun `bytes with an unknown direction are rejected`() {
        assertThrows<IllegalArgumentException> { PageCursor.fromBytes(packed(direction = 9, id = "id")) }
    }

    @Test
    fun `bytes without an id are rejected`() {
        assertThrows<IllegalArgumentException> { PageCursor.fromBytes(packed(direction = 1, id = "")) }
    }

    @Test
    fun `too few bytes are rejected`() {
        assertThrows<IllegalArgumentException> { PageCursor.fromBytes(byteArrayOf(1, 2, 3)) }
    }

    private fun packed(
        direction: Byte,
        id: String,
    ): ByteArray {
        val idBytes = id.toByteArray(Charsets.UTF_8)
        return ByteBuffer
            .allocate(9 + idBytes.size)
            .put(direction)
            .putLong(20)
            .put(idBytes)
            .array()
    }
}
