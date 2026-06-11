package no.novari.fint.core.provider.datasync.ingest

import no.fintlabs.adapter.models.sync.SyncPage
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Service
class SyncIngestProducer(
    @Qualifier("syncIngestKafkaTemplate") private val kafkaTemplate: KafkaTemplate<String, SyncIngestRecord>,
    private val topics: SyncIngestTopics,
    private val properties: SyncIngestProperties,
) {
    fun publish(
        resourceKey: String,
        page: SyncPage,
    ) {
        val futures =
            page.resources.map { entry ->
                kafkaTemplate.send(
                    topics.topic,
                    "$resourceKey:${entry.identifier}",
                    SyncIngestRecord(
                        resourceKey = resourceKey,
                        identifier = entry.identifier,
                        orgId = page.metadata.orgId,
                        corrId = page.metadata.corrId,
                        syncType = page.syncType,
                        totalSize = page.metadata.totalSize,
                        time = page.metadata.time,
                        resource = entry.resource,
                    ),
                )
            }
        CompletableFuture
            .allOf(*futures.toTypedArray())
            .get(properties.sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }
}
