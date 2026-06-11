package no.novari.fint.core.provider.datasync.ingest

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class SyncIngestTopics(
    @Value("\${novari.kafka.topic.org-id}") orgId: String,
    @Value("\${novari.kafka.topic.domain-context}") domainContext: String,
) {
    val topic = "$orgId.$domainContext.provider-sync"
    val dlt = "$topic.DLT"
    val groupId = "$topic.writer"
}
