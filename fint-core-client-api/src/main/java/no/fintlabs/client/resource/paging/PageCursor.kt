package no.fintlabs.client.resource.paging

import no.novari.core.shared.store.PageAnchor
import java.nio.ByteBuffer
import java.time.Instant

/**
 * A bookmark in a list read. [anchor] is the entry the bookmark points at and [direction] says
 * whether the next read continues after it or before it. [PageCursorCodec] turns a cursor into
 * the token clients see and back again.
 */
data class PageCursor(
    val direction: PageDirection,
    val anchor: PageAnchor,
) {
    fun toBytes(): ByteArray {
        val id = anchor.id.toByteArray(Charsets.UTF_8)

        return ByteBuffer
            .allocate(HEADER_BYTES + id.size)
            .put(direction.code)
            .putLong(anchor.createdAt.toEpochMilli())
            .put(id)
            .array()
    }

    companion object {
        private const val HEADER_BYTES = Byte.SIZE_BYTES + Long.SIZE_BYTES

        fun fromBytes(bytes: ByteArray): PageCursor {
            require(bytes.size > HEADER_BYTES) { "Not a page cursor" }

            val buffer = ByteBuffer.wrap(bytes)
            val code = buffer.get()
            val direction =
                PageDirection.entries.firstOrNull { it.code == code }
                    ?: throw IllegalArgumentException("Not a page cursor")
            val createdAt = Instant.ofEpochMilli(buffer.getLong())
            val id = String(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES, Charsets.UTF_8)

            return PageCursor(direction, PageAnchor(createdAt, id))
        }
    }
}
