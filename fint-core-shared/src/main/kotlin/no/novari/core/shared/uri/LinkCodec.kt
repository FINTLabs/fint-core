package no.novari.core.shared.uri

import org.springframework.web.util.UriUtils

/**
 * The percent-codec for link id values. The information model does the structural split and holds no
 * codec, so this is the only place a value is encoded or decoded.
 *
 * Only the id value passes through here. Id field names are model-declared and always safe, and the
 * path separators are written during the join, never encoded.
 *
 * RFC 3986 path-segment rules, not `application/x-www-form-urlencoded`: `URLEncoder` would render a
 * space as `+` and turn a literal `+` back into a space.
 */
object LinkCodec {
    fun encodeIdValue(value: String): String = UriUtils.encodePathSegment(value, Charsets.UTF_8)

    /**
     * Adapters that have not moved to the encoded contract still send raw values, and a raw value
     * containing a stray `%` is not a valid escape sequence. Keeping it verbatim is closer to the
     * truth than failing the whole sync page.
     */
    fun decodeIdValue(value: String): String = runCatching { UriUtils.decode(value, Charsets.UTF_8) }.getOrDefault(value)
}
