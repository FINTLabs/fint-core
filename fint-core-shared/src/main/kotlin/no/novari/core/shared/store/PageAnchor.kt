package no.novari.core.shared.store

import java.time.Instant

data class PageAnchor(
    val createdAt: Instant,
    val id: String,
)
