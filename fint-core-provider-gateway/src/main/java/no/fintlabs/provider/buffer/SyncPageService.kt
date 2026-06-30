package no.fintlabs.provider.buffer

import lombok.RequiredArgsConstructor
import no.fintlabs.adapter.models.sync.SyncPage
import no.fintlabs.adapter.models.sync.SyncType
import no.novari.core.shared.model.ResourceCoordinate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import kotlin.time.measureTime

@RequiredArgsConstructor
@Service
class SyncPageService(
    private val bufferWriter: BufferWriter,
    private val metaDataKafkaProducer: MetaDataKafkaProducer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun <T : SyncPage> doSync(
        syncPage: T,
        coords: ResourceCoordinate,
    ) = syncPage.logSync {
        if (syncPage.syncType == SyncType.DELETE) {
            syncPage.resources.forEach { syncPageEntry -> syncPageEntry.resource = null }
        }

        // Set time of SyncPage to when we processed it
        // This lets us see in Status-Service when we have processed this page
        syncPage.metadata.time = System.currentTimeMillis()

        val syncType = syncPage.syncType.toString().lowercase()
        val eventName = "adapter-$syncType-sync"
        metaDataKafkaProducer.send(syncPage.metadata, eventName) // Send to Status-Service

        sendToBuffer(syncPage, coords)
    }

    private fun sendToBuffer(
        page: SyncPage,
        coords: ResourceCoordinate,
    ) {
        val futures =
            page.resources.map { syncPageEntry ->
                bufferWriter
                    .sendSyncEntity(page, syncPageEntry, coords)
                    .whenComplete { _, throwable -> logSendOutcome(page, throwable) }
            }
        CompletableFuture.allOf(*futures.toTypedArray()).join()
    }

    private fun logSendOutcome(
        page: SyncPage,
        throwable: Throwable?,
    ) {
        if (throwable == null) {
            log.debug(
                "Successfully sent entity [orgId={}, uriRef={}]",
                page.metadata.orgId,
                page.metadata.uriRef,
            )
        } else {
            log.error(
                "Failed to send entity [orgId={}, uriRef={}]: {}",
                page.metadata.orgId,
                page.metadata.uriRef,
                throwable.message,
                throwable,
            )
        }
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
