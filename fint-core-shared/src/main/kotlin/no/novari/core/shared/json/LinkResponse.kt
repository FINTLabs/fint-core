package no.novari.core.shared.json

/**
 * A link as the client receives it. The stored `Link` keeps an id field and value; this is the
 * rendered href, built from the owning resource's metadata at response time.
 */
data class LinkResponse(
    val href: String,
)
