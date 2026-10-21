package no.novari.core.shared.json

import no.novari.fint.core.model.personvern.samtykke.Samtykke
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.core.JacksonException
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class WithDateTime(
    val at: LocalDateTime?,
)

private data class WithDate(
    val on: LocalDate?,
)

class LenientDateDeserializerTest {
    private val mapper = FintJson.storageMapper()

    private fun dateTime(value: Any) = mapper.convertValue(mapOf("at" to value), WithDateTime::class.java).at

    private fun date(value: Any) = mapper.convertValue(mapOf("on" to value), WithDate::class.java).on

    @ParameterizedTest
    @ValueSource(
        strings = [
            "2026-10-21T07:00:00.000+00:00",
            "2026-10-21T09:00:00+02:00",
            "2026-10-21T09:00:00.000+0200",
            "2026-10-21T08:00:00+01",
            "2026-10-21T07:00:00Z",
            "2026-10-21T07:00:00.000Z",
            "2026-10-21t07:00:00z",
        ],
    )
    fun `a date-time with an offset is moved to UTC`(sent: String) {
        assertEquals(LocalDateTime.of(2026, 10, 21, 7, 0), dateTime(sent))
    }

    @ParameterizedTest
    @ValueSource(strings = ["2026-10-21T07:00:00", "2026-10-21T07:00", " 2026-10-21T07:00:00 "])
    fun `a date-time without an offset is kept as written`(sent: String) {
        assertEquals(LocalDateTime.of(2026, 10, 21, 7, 0), dateTime(sent))
    }

    @Test
    fun `a bare date is read as the start of its day`() {
        assertEquals(LocalDateTime.of(2026, 10, 21, 0, 0), dateTime("2026-10-21"))
    }

    @Test
    fun `epoch milliseconds are read as UTC`() {
        assertEquals(LocalDateTime.of(2026, 10, 21, 7, 0), dateTime(1792566000000))
    }

    @Test
    fun `the stored form reads back unchanged`() {
        val stored = WithDateTime(LocalDateTime.of(2026, 10, 21, 7, 0, 0, 123_000_000))

        assertEquals(stored, mapper.readValue(mapper.writeValueAsString(stored), WithDateTime::class.java))
    }

    @Test
    fun `an empty string reads as null`() {
        assertNull(dateTime(""))
        assertNull(date(""))
    }

    @Test
    fun `a string that is not a date fails with the string in the message`() {
        val error = assertFailsWith<JacksonException> { dateTime("21.10.2026") }

        assertTrue("21.10.2026" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a model date-time sent with millis and a colon offset is read`() {
        val samtykke =
            mapper.convertValue(
                mapOf("gyldighetsperiode" to mapOf("start" to "2026-10-21T07:00:00.000+00:00")),
                Samtykke::class.java,
            )

        assertEquals(LocalDateTime.of(2026, 10, 21, 7, 0), samtykke.gyldighetsperiode?.start)
    }

    @Test
    fun `a bare date is kept as written`() {
        assertEquals(LocalDate.of(2010, 5, 15), date("2010-05-15"))
    }

    @ParameterizedTest
    @CsvSource(
        "2010-05-14T22:00:00Z, 2010-05-15",
        "2010-05-14T22:00:00.000+00:00, 2010-05-15",
        "2010-05-15T00:00:00Z, 2010-05-15",
        "2010-05-15T00:00:00+02:00, 2010-05-15",
        "2010-01-14T23:00:00Z, 2010-01-15",
    )
    fun `a date-time with an offset gives its date in Oslo`(
        sent: String,
        expected: LocalDate,
    ) {
        assertEquals(expected, date(sent))
    }

    @Test
    fun `a date-time without an offset keeps its date`() {
        assertEquals(LocalDate.of(2010, 5, 15), date("2010-05-15T23:30:00"))
    }

    @Test
    fun `epoch milliseconds give their date in Oslo`() {
        assertEquals(LocalDate.of(2010, 5, 15), date(1273874400000))
    }
}
