package no.novari.core.shared.kafka

import no.novari.core.shared.model.OrgId

object KafkaTopicNames {
    private const val DOMAIN_CONTEXT = "fint-core"

    @JvmStatic
    fun eventTopic(
        orgId: OrgId,
        eventName: String,
    ): String = "${orgId.asTopicSegment}.$DOMAIN_CONTEXT.event.$eventName"

    @JvmStatic
    fun eventTopic(
        orgId: String,
        eventName: String,
    ): String = eventTopic(OrgId.from(orgId), eventName)

    @JvmStatic
    fun entityTopic(
        orgId: OrgId,
        resourceName: String,
    ): String = "${orgId.asTopicSegment}.$DOMAIN_CONTEXT.entity.$resourceName"

    @JvmStatic
    fun entityTopic(
        orgId: String,
        resourceName: String,
    ): String = entityTopic(OrgId.from(orgId), resourceName)
}
