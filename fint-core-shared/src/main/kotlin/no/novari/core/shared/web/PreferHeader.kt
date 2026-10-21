package no.novari.core.shared.web

import java.time.Duration

/** The preferences a client sent in a `Prefer` header (RFC 7240), such as `respond-async, wait=10`. */
class PreferHeader private constructor(
    private val preferences: Map<String, String?>,
) {
    val respondAsync: Boolean get() = RESPOND_ASYNC in preferences

    /** The longest the client will wait, or null when it did not say in whole seconds. */
    val wait: Duration?
        get() =
            preferences[WAIT]
                // A value the server cannot read is ignored, not refused, as the RFC says.
                ?.takeIf { value -> value.all { it in '0'..'9' } }
                ?.toLongOrNull()
                ?.let(Duration::ofSeconds)

    companion object {
        const val NAME = "Prefer"
        const val APPLIED = "Preference-Applied"
        const val RESPOND_ASYNC = "respond-async"
        const val WAIT = "wait"

        fun parse(header: String?): PreferHeader {
            val preferences = LinkedHashMap<String, String?>()
            header.orEmpty().splitOutsideQuotes(',').forEach { preference ->
                val token = preference.splitOutsideQuotes(';').first()
                val name = token.substringBefore('=').trim().lowercase()
                // Only the first of a repeated preference counts, as the RFC says.
                if (name.isEmpty() || name in preferences) return@forEach
                preferences[name] =
                    token
                        .substringAfter('=', "")
                        .trim()
                        .unquoted()
                        .ifEmpty { null }
            }
            return PreferHeader(preferences)
        }

        // A quoted value may hold the separator, and a backslash in it escapes the next character.
        private fun String.splitOutsideQuotes(separator: Char): List<String> {
            val parts = mutableListOf<String>()
            val part = StringBuilder()
            var quoted = false
            var escaped = false
            for (c in this) {
                if (!quoted && c == separator) {
                    parts += part.toString()
                    part.clear()
                    continue
                }
                when {
                    escaped -> escaped = false
                    quoted && c == '\\' -> escaped = true
                    c == '"' -> quoted = !quoted
                }
                part.append(c)
            }
            parts += part.toString()
            return parts
        }

        private fun String.unquoted(): String {
            if (length < 2 || first() != '"' || last() != '"') return this
            val text = StringBuilder()
            var escaped = false
            for (c in substring(1, length - 1)) {
                if (!escaped && c == '\\') {
                    escaped = true
                } else {
                    text.append(c)
                    escaped = false
                }
            }
            return text.toString()
        }
    }
}
