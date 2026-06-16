package no.fintlabs.consumer.config

@JvmInline
value class OrgId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "OrgId must not be blank" }
        require(value == value.lowercase()) { "OrgId must be lowercase: $value" }
        require(!value.contains('-')) { "OrgId must use dot instead of dash: $value" }
        require(!value.contains('_')) { "OrgId must use dot instead of underscore: $value" }
    }

    val asTopicSegment: String
        get() = value.replace(".", "-")

    fun matches(rawValue: String): Boolean = this == from(rawValue)

    override fun toString(): String = value

    companion object {
        private val separatorPattern = Regex("[_-]")

        fun from(rawValue: String): OrgId = OrgId(rawValue.trim().lowercase().replace(separatorPattern, "."))

        fun fromTopicSegment(topicSegment: String): OrgId = from(topicSegment)
    }
}
