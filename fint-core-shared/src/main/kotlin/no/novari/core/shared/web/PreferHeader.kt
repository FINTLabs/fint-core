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
            header.orEmpty().split(',').forEach { preference ->
                val token = preference.substringBefore(';')
                val name = token.substringBefore('=').trim().lowercase()
                // Only the first of a repeated preference counts, as the RFC says.
                if (name.isEmpty() || name in preferences) return@forEach
                preferences[name] =
                    token
                        .substringAfter('=', "")
                        .trim()
                        .removeSurrounding("\"")
                        .ifEmpty { null }
            }
            return PreferHeader(preferences)
        }
    }
}
