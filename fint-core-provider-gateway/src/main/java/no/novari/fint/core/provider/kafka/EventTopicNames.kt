package no.novari.fint.core.provider.kafka

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.regex.Pattern

@Component
class EventTopicNames(
    @Value("\${novari.kafka.topic.org-id}") val defaultOrgId: String,
    @Value("\${novari.kafka.topic.domain-context}") private val domainContext: String,
) {
    @JvmOverloads
    fun event(
        eventName: String,
        orgId: String = defaultOrgId,
    ) = "$orgId.$domainContext.event.$eventName"

    @JvmOverloads
    fun entity(
        resourceName: String,
        orgId: String = defaultOrgId,
    ) = "$orgId.$domainContext.entity.$resourceName"

    fun eventPattern(
        orgIds: Collection<String>,
        eventNames: Collection<String>,
    ): Pattern =
        Pattern.compile(
            "^${anyOf(orgIds)}\\.${Pattern.quote(domainContext)}\\.event\\.${anyOf(eventNames)}$",
        )

    private fun anyOf(values: Collection<String>) = "(?:${values.joinToString("|") { Pattern.quote(it) }})"
}
