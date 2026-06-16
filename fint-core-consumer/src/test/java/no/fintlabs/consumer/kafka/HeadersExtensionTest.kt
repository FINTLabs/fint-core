package no.fintlabs.consumer.kafka

import io.mockk.every
import io.mockk.mockk
import org.apache.kafka.common.header.Header
import org.apache.kafka.common.header.Headers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer

class HeadersExtensionTest {
    private val headerKey = "key"

    @Test
    fun `byteValue returns byte value when the header exist and contains only a single byte`() {
        // Given
        val headers = mockHeaders(byteArrayOf(42))

        // When
        val byteValue = headers.byteValue(headerKey)

        // Then
        assertEquals(42.toByte(), byteValue)
    }

    @Test
    fun `byteValue returns header byte value when the header exist and is an int`() {
        // Given
        val int = 5
        val intByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(int).array()

        val headers = mockHeaders(intByteArray)

        // When
        val byteValue = headers.byteValue(headerKey)

        // Then
        assertEquals(int.toByte(), byteValue)
    }

    @Test
    fun `byteValue returns null when the header does not exist`() {
        // Given
        val headers = mockEmptyHeaders()

        // When
        val byteValue = headers.byteValue(headerKey)

        // Then
        assertNull(byteValue)
    }

    @Test
    fun `byteValue throws exception when the header exist and contains multiple bytes`() {
        // Given
        val record = mockHeaders(byteArrayOf(42, 10))

        // Then
        assertThrows<IllegalArgumentException> { record.byteValue(headerKey) }
    }

    @Test
    fun `stringValue turns byteArray to String`() {
        val value = "KafkaTest"
        val record = mockHeaders(value.toByteArray())

        // When
        val headerStringValue = record.stringValue(headerKey)

        // Then
        assertEquals(value, headerStringValue)
    }

    @Test
    fun `stringValue returns null when header is not present`() {
        // Given
        val record = mockEmptyHeaders()

        // When
        val headerLongValue = record.stringValue(headerKey)

        // Then
        assertNull(headerLongValue)
    }

    @Test
    fun `longValue turns byteArray to Long`() {
        // Given
        val longValue = 123456789L
        val longAsByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(longValue).array()
        val record = mockHeaders(longAsByteArray)

        // When
        val headerLongValue = record.longValue(headerKey)

        // Then
        assertEquals(longValue, headerLongValue)
    }

    @Test
    fun `longValue returns null when header is not present`() {
        // Given
        val record = mockEmptyHeaders()

        // When
        val headerLongValue = record.longValue(headerKey)

        // Then
        assertNull(headerLongValue)
    }

    private fun mockHeaders(value: ByteArray) =
        mockk<Headers> {
            every { lastHeader(headerKey) } returns mockHeader(value)
        }

    private fun mockEmptyHeaders() =
        mockk<Headers> {
            every { lastHeader(any()) } returns null
        }

    private fun mockHeader(value: ByteArray) =
        mockk<Header> {
            every { value() } returns value
        }
}
