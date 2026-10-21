package no.novari.core.shared.web

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreferHeaderTest {
    @Test
    fun `a missing header prefers nothing`() {
        val prefer = PreferHeader.parse(null)

        assertFalse(prefer.respondAsync)
        assertNull(prefer.wait)
    }

    @Test
    fun `respond-async is found among other preferences in any case`() {
        assertTrue(PreferHeader.parse("return=minimal, Respond-Async; foo=bar").respondAsync)
    }

    @Test
    fun `wait is read in whole seconds, with space around the equals sign`() {
        assertEquals(Duration.ofSeconds(90), PreferHeader.parse("wait=90").wait)
        assertEquals(Duration.ofSeconds(5), PreferHeader.parse("respond-async, wait = 5").wait)
        assertEquals(Duration.ZERO, PreferHeader.parse("wait=0").wait)
    }

    @Test
    fun `a wait that is not a whole number of seconds is ignored`() {
        assertNull(PreferHeader.parse("wait").wait)
        assertNull(PreferHeader.parse("wait=").wait)
        assertNull(PreferHeader.parse("wait=-1").wait)
        assertNull(PreferHeader.parse("wait=+5").wait)
        assertNull(PreferHeader.parse("wait=1.5").wait)
        assertNull(PreferHeader.parse("wait=soon").wait)
        assertNull(PreferHeader.parse("wait=99999999999999999999").wait)
    }

    @Test
    fun `a comma inside a quoted value does not start a new preference`() {
        assertNull(PreferHeader.parse("""extension="x,wait=90,y"""").wait)
        assertEquals(Duration.ofSeconds(10), PreferHeader.parse("""extension="x,wait=0,y", wait=10""").wait)
    }

    @Test
    fun `an escaped quote does not end a quoted value`() {
        assertEquals(Duration.ofSeconds(5), PreferHeader.parse("""extension="a\",wait=90", wait=5""").wait)
        assertEquals(Duration.ofSeconds(7), PreferHeader.parse("""extension="a\\", wait=7""").wait)
    }

    @Test
    fun `a quoted parameter value does not leak into the preferences`() {
        val prefer = PreferHeader.parse("""respond-async; foo="a,wait=90;b"""")

        assertTrue(prefer.respondAsync)
        assertNull(prefer.wait)
    }

    @Test
    fun `an unterminated quote swallows the rest of the header`() {
        assertNull(PreferHeader.parse("""extension="x, wait=90""").wait)
    }

    @Test
    fun `only the first of a repeated preference counts`() {
        assertEquals(Duration.ofSeconds(10), PreferHeader.parse("wait=10, wait=60").wait)
        assertNull(PreferHeader.parse("wait=soon, wait=60").wait)
    }
}
