package no.fintlabs.client.resource.paging

import no.novari.core.shared.store.PageAnchor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A cursor is a bookmark the client gets in a link and sends back unchanged. These tests check
 * that a cursor survives that round trip, that the token is safe to put in a URL, and that
 * anything else sent as a cursor is rejected instead of being read as a bookmark.
 */
class PageCursorTest {
    private val anchor = PageAnchor(Instant.parse("2026-09-10T10:25:41.103Z"), "fravarsregistrering-103508")

    @Test
    fun `a cursor survives encoding and decoding in both directions`() {
        PageDirection.entries.forEach { direction ->
            val cursor = PageCursor(direction, anchor)

            assertEquals(cursor, PageCursor.decode(cursor.encode()))
        }
    }

    @Test
    fun `an id with characters outside ascii survives the round trip`() {
        val cursor = PageCursor(PageDirection.AFTER, PageAnchor(Instant.ofEpochMilli(20), "sander-fravær/æøå"))

        assertEquals(cursor, PageCursor.decode(cursor.encode()))
    }

    @Test
    fun `the token only holds characters that need no escaping in a query string`() {
        val token = PageCursor(PageDirection.BEFORE, anchor).encode()

        assertTrue(token.matches(Regex("[A-Za-z0-9_-]+")), token)
    }

    @Test
    fun `text that is not base64 is rejected`() {
        assertThrows<IllegalArgumentException> { PageCursor.decode("%%%not-base64%%%") }
    }

    @Test
    fun `a token with the wrong number of parts is rejected`() {
        assertThrows<IllegalArgumentException> { PageCursor.decode(token("a", "20")) }
        assertThrows<IllegalArgumentException> { PageCursor.decode(token("a", "20", "id", "extra")) }
    }

    @Test
    fun `a token with an unknown direction is rejected`() {
        assertThrows<IllegalArgumentException> { PageCursor.decode(token("x", "20", "id")) }
    }

    @Test
    fun `a token whose timestamp is not a number is rejected`() {
        assertThrows<IllegalArgumentException> { PageCursor.decode(token("a", "yesterday", "id")) }
    }

    @Test
    fun `a token without an id is rejected`() {
        assertThrows<IllegalArgumentException> { PageCursor.decode(token("a", "20", "")) }
    }

    private fun token(vararg parts: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            parts.joinToString(Char(31).toString()).toByteArray(Charsets.UTF_8),
        )
}
