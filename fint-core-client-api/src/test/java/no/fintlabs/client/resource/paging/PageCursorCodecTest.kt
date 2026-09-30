package no.fintlabs.client.resource.paging

import no.novari.core.shared.store.PageAnchor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The token a client gets is an encrypted cursor. These tests check that the server gets its own
 * cursor back, that the client cannot read the id out of the token, and that a token that was
 * changed or made with another key is rejected instead of being read as a bookmark.
 */
class PageCursorCodecTest {
    private val codec = PageCursorCodec.withRandomKey()
    private val id = "01010112345"
    private val cursor = PageCursor(PageDirection.AFTER, PageAnchor(Instant.parse("2026-09-10T10:25:41.103Z"), id))

    @Test
    fun `a cursor survives encoding and decoding in both directions`() {
        PageDirection.entries.forEach { direction ->
            val original = cursor.copy(direction = direction)

            assertEquals(original, codec.decode(codec.encode(original)))
        }
    }

    @Test
    fun `the token only holds characters that need no escaping in a query string`() {
        val token = codec.encode(cursor)

        assertTrue(token.matches(Regex("[A-Za-z0-9_-]+")), token)
    }

    @Test
    fun `the id cannot be read out of the token`() {
        val decoded = String(Base64.getUrlDecoder().decode(codec.encode(cursor)), Charsets.ISO_8859_1)

        assertFalse(decoded.contains(id))
    }

    @Test
    fun `the same cursor gives a different token every time and both decode to it`() {
        val first = codec.encode(cursor)
        val second = codec.encode(cursor)

        assertNotEquals(first, second)
        assertEquals(cursor, codec.decode(first))
        assertEquals(cursor, codec.decode(second))
    }

    @Test
    fun `a token made with another key is rejected`() {
        val tokenFromAnotherKey = PageCursorCodec.withRandomKey().encode(cursor)

        assertThrows<IllegalArgumentException> { codec.decode(tokenFromAnotherKey) }
    }

    @Test
    fun `a token with one changed byte is rejected`() {
        val bytes = Base64.getUrlDecoder().decode(codec.encode(cursor))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        val changed = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        assertThrows<IllegalArgumentException> { codec.decode(changed) }
    }

    @Test
    fun `text that is not base64 is rejected`() {
        assertThrows<IllegalArgumentException> { codec.decode("%%%not-base64%%%") }
    }

    @Test
    fun `a token that is too short to hold a cursor is rejected`() {
        assertThrows<IllegalArgumentException> { codec.decode("QUJD") }
    }
}
