package no.fintlabs.client.resource.event

/**
 * The preferences a client sent in a `Prefer` header (RFC 7240). Preferences are separated by
 * commas, and each may carry a value after an equals sign or parameters after a semicolon, as
 * in `respond-async, wait=10`. Only the names are kept, in lowercase.
 */
class PreferHeader private constructor(
    private val preferences: Set<String>,
) {
    val respondAsync: Boolean get() = RESPOND_ASYNC in preferences

    companion object {
        const val NAME = "Prefer"
        const val APPLIED = "Preference-Applied"
        const val RESPOND_ASYNC = "respond-async"

        fun parse(header: String?): PreferHeader =
            PreferHeader(
                header
                    .orEmpty()
                    .split(',')
                    .map {
                        it
                            .substringBefore(';')
                            .substringBefore('=')
                            .trim()
                            .lowercase()
                    }.filterTo(HashSet()) { it.isNotEmpty() },
            )
    }
}
