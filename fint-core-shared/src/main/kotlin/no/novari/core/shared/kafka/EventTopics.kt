package no.novari.core.shared.kafka

import no.novari.core.shared.model.OrgId

/**
 * The event feed topics, one pair per PRIMARY organization: sub-org events ride the primary's
 * topics, and the record payload's own orgId field says which asset an event belongs to.
 * Neither consumer nor provider consumes these; they exist as a feed for the external status
 * service project, which observes the event flow from outside this codebase. The Mongo event
 * store is the source of truth, so publishing here is best-effort and must never fail the
 * request that triggered it.
 */
object EventTopics {
    fun requestTopic(orgId: OrgId): String = topicName(orgId, "request")

    fun responseTopic(orgId: OrgId): String = topicName(orgId, "response")

    private fun topicName(
        orgId: OrgId,
        suffix: String,
    ): String = "${orgId.asTopicSegment}.fint-core.fint-felleskomponent-event-$suffix"
}
