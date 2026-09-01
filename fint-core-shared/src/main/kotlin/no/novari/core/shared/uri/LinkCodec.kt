package no.novari.core.shared.uri

import org.springframework.web.util.UriUtils

object LinkCodec {
    fun encodeIdValue(value: String): String = UriUtils.encodePathSegment(value, Charsets.UTF_8)

    fun decodeIdValue(value: String): String = runCatching { UriUtils.decode(value, Charsets.UTF_8) }.getOrDefault(value)
}
