package no.novari.fint.core.provider.datasync

import lombok.RequiredArgsConstructor
import no.fintlabs.adapter.models.sync.SyncPage
import no.fintlabs.adapter.models.sync.SyncPageMetadata
import no.fintlabs.adapter.models.sync.SyncType
import no.novari.fint.core.provider.datasync.ingest.SyncIngestProducer
import no.novari.fint.core.shared.resource.ResourceRef
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.time.measureTime

@RequiredArgsConstructor
@Service
class SyncPageService(
    private val metaDataKafkaProducer: MetaDataKafkaProducer,
    private val syncIngestProducer: SyncIngestProducer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun <T : SyncPage> doSync(
        syncPage: T,
        domainName: String,
        packageName: String,
        entity: String,
    ) = syncPage.logSync {
        if (syncPage.syncType == SyncType.DELETE) {
            syncPage.resources.forEach { syncPageEntry -> syncPageEntry.resource = null }
        }

        mutateMetadata(syncPage.metadata, domainName, packageName, entity)
        val resourceKey = ResourceRef.keyOf(domainName, packageName, entity)
        syncIngestProducer.publish(resourceKey, syncPage)
        val syncType = syncPage.syncType.toString().lowercase()
        val eventName = "adapter-$syncType-sync"
        metaDataKafkaProducer.send(syncPage.metadata, eventName)
    }

    private fun mutateMetadata(
        syncPageMetadata: SyncPageMetadata,
        domainName: String,
        packageName: String,
        resourceName: String,
    ) {
        syncPageMetadata.time = System.currentTimeMillis()
        syncPageMetadata.uriRef = domainName.lowercase() + '/' + packageName.lowercase() + '/' + resourceName.lowercase()
    }

    private inline fun SyncPage.logSync(action: () -> Unit) {
        log.info(
            "Start {} sync: {}({}), {}, total size: {}, page size: {}, page: {}, total pages: {}",
            syncType.toString().lowercase(),
            metadata.corrId,
            metadata.orgId,
            metadata.uriRef,
            metadata.totalSize,
            resources.size,
            metadata.page,
            metadata.totalPages,
        )

        val timeElapsed =
            measureTime {
                action()
            }

        log.info(
            "Processed {} sync {}/{} for {}: duration={}ms, total size={}, page size={}, page={}, total pages={}",
            syncType.toString().lowercase(),
            metadata.orgId,
            metadata.corrId,
            metadata.uriRef,
            timeElapsed.inWholeMilliseconds,
            metadata.totalSize,
            resources.size,
            metadata.page,
            metadata.totalPages,
        )
    }
}
