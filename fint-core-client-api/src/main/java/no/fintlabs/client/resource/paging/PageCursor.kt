package no.fintlabs.client.resource.paging

import no.novari.core.shared.store.PageAnchor
import java.time.Instant
import java.util.Base64

data class PageCursor(
    val direction: PageDirection,
    val anchor: PageAnchor,
) {
    fun encode(): String =
        listOf(direction.code, anchor.createdAt.toEpochMilli().toString(), anchor.id)
            .joinToString(SEPERATOR)
            .toByteArray(Charsets.UTF_8)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    companion object {
        private const val SEPERATOR = "\u001f"

        fun decode(token: String): PageCursor {
            val decoded =
                try {
                    String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("Not a page cursor", e)
                }

            val parts = decoded.split(SEPERATOR)
            require(parts.size == 3 && parts[2].isNotEmpty()) { "Not a page cursor" }
            val direction =
                PageDirection.entries.firstOrNull { it.code == parts[0] }
                    ?: throw IllegalArgumentException("Not a page cursor")
            val millis = parts[1].toLongOrNull() ?: throw IllegalArgumentException("Not a page cursor")
            return PageCursor(direction, PageAnchor(Instant.ofEpochMilli(millis), parts[2]))
        }
    }
}
