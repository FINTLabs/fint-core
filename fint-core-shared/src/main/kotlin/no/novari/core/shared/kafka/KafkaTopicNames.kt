package no.novari.core.shared.kafka

object KafkaTopicNames {
    private const val DOMAIN_CONTEXT = "fint-core"

    @JvmStatic
    fun eventTopic(eventName: String): String = "novari-no.$DOMAIN_CONTEXT.$eventName"
}
