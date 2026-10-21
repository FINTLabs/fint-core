package no.novari.core.shared.json

import no.novari.fint.core.model.felles.kompleksedatatyper.Periode
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals

class ModelDatesTest {
    private val storageMapper = FintJson.storageMapper()

    private val sevenUtc = Date.from(Instant.parse("2026-10-21T07:00:00Z"))

    private fun start(sent: Any) = storageMapper.convertValue(mapOf("start" to sent), Periode::class.java).start

    @ParameterizedTest
    @ValueSource(
        strings = [
            "2026-10-21T07:00:00.000+00:00",
            "2026-10-21T07:00:00+0000",
            "2026-10-21T07:00:00Z",
            "2026-10-21T09:00:00+02:00",
            "2026-10-21T07:00:00",
        ],
    )
    fun `a date is read from any ISO 8601 form as the instant it names, UTC when it has no offset`(sent: String) {
        assertEquals(sevenUtc, start(sent))
    }

    @Test
    fun `a date is read from epoch milliseconds`() {
        assertEquals(sevenUtc, start(1792566000000))
    }

    @Test
    fun `a bare date is read as midnight UTC`() {
        assertEquals(Date.from(Instant.parse("2026-10-21T00:00:00Z")), start("2026-10-21"))
    }

    @Test
    fun `a date is written in UTC ending in Z, without milliseconds`() {
        val json =
            FintJson
                .responseMapper("https://api.felleskomponent.no")
                .writeValueAsString(Periode(start = Date.from(Instant.parse("2026-02-03T04:05:06.789Z"))))

        assertEquals("""{"start":"2026-02-03T04:05:06Z"}""", json)
    }

    @Test
    fun `the storage form keeps the milliseconds`() {
        val json = storageMapper.writeValueAsString(Periode(start = Date.from(Instant.parse("2026-02-03T04:05:06.789Z"))))

        assertEquals("""{"start":"2026-02-03T04:05:06.789Z"}""", json)
    }
}
