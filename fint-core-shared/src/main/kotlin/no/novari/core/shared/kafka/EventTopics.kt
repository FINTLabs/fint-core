package no.novari.core.shared.kafka

object EventTopics {
    fun requestTopic(): String = topicName("request")

    fun responseTopic(): String = topicName("response")

    private fun topicName(suffix: String): String = "novari-no.fint-core.fint-felleskomponent-event-$suffix"
}
