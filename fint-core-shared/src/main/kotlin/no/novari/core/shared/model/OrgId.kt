package no.novari.core.shared.model

/**
 * An org id in its dotted lowercase form, such as `ude.oslo.kommune.no`. [from] is the only way to
 * make one, and it accepts dots, dashes or underscores in any case, so every OrgId in the code is
 * already in that form. It is a normal class and not a value class, because Spring builds a value
 * class from request input by calling its constructor, which would skip [from].
 */
class OrgId private constructor(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "OrgId must not be blank" }
    }

    val asTopicSegment: String
        get() = value.replace(".", "-")

    fun matches(rawValue: String): Boolean = this == from(rawValue)

    /**
     * Whether this org is [organization] itself or one of its sub-orgs, e.g. `test.novari.no`
     * belongs to `novari.no`. The dot anchors the suffix so `fintlabs.no` never belongs to
     * `labs.no`.
     */
    fun belongsTo(organization: OrgId): Boolean = this == organization || value.endsWith(".${organization.value}")

    override fun equals(other: Any?): Boolean = other is OrgId && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        private val separatorPattern = Regex("[_-]")

        fun from(rawValue: String): OrgId = OrgId(rawValue.trim().lowercase().replace(separatorPattern, "."))

        fun fromTopicSegment(topicSegment: String): OrgId = from(topicSegment)
    }
}
