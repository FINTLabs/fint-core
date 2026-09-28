package no.novari.core.shared.model

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
