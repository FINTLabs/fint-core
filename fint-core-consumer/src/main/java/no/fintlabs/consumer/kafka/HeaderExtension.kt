package no.fintlabs.consumer.kafka

import org.apache.kafka.common.header.Headers
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

internal fun Headers.byteValue(name: String): Byte? {
    val value = lastHeader(name)?.value() ?: return null

    if (value.size == 1) {
        return value[0]
    } else if (value.size == 4) {
        // TODO: Handle int temporarily - until all messages in Kafka is Bytes
        return ByteBuffer.wrap(value).int.toByte()
    }

    throw IllegalArgumentException("Header '$name' contains ${value.size} bytes, but expected 1 or 4")
}

internal fun Headers.stringValue(name: String): String? = lastHeader(name)?.value()?.toString(StandardCharsets.UTF_8)

internal fun Headers.longValue(name: String): Long? =
    lastHeader(name)?.value()?.let {
        if (it.size >= 8) ByteBuffer.wrap(it).long else null
    }
