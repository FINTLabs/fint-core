package no.novari.fint.core.provider.datasync.ingest

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class SyncIngestTopics(
    @Value("\${novari.kafka.topic.org-id}") orgId: String,
    @Value("\${novari.kafka.topic.domain-context}") private val domainContext: String,
) {
    val topic = forOrg(orgId)
    val dlt = dltForOrg(orgId)
    val groupId = "$topic.writer"

    fun forOrg(orgId: String) = "$orgId.$domainContext.$NAME"

    fun dltForOrg(orgId: String) = "${forOrg(orgId)}.DLT"

    companion object {
        const val NAME = "entity.adapter-sync"
    }
}
