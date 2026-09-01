package no.novari.core.shared.kafka

import no.novari.core.shared.model.OrgId

object EventTopics {
    fun requestTopic(orgId: OrgId): String = topicName(orgId, "request")

    fun responseTopic(orgId: OrgId): String = topicName(orgId, "response")

    private fun topicName(
        orgId: OrgId,
        suffix: String,
    ): String = "${orgId.asTopicSegment}.fint-core.fint-felleskomponent-event-$suffix"
}
