package no.novari.core.shared.json

import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals

private data class WithTimestamp(
    val at: LocalDateTime,
)

class ResponseDatesTest {
    @Test
    fun `a resource LocalDateTime is written as an ISO 8601 string ending in Z`() {
        val json =
            FintJson
                .responseMapper("https://api.felleskomponent.no")
                .writeValueAsString(WithTimestamp(LocalDateTime.of(2026, 2, 3, 4, 5, 6)))

        assertEquals("""{"at":"2026-02-03T04:05:06Z"}""", json)
    }

    @Test
    fun `the storage form keeps the plain local format, since it never reaches a county`() {
        val json = FintJson.storageMapper().writeValueAsString(WithTimestamp(LocalDateTime.of(2026, 2, 3, 4, 5, 6)))

        assertEquals("""{"at":"2026-02-03T04:05:06"}""", json)
    }
}
