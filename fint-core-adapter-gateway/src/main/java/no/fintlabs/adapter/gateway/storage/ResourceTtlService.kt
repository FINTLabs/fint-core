package no.fintlabs.adapter.gateway.storage

import no.fintlabs.adapter.gateway.config.ProviderProperties
import no.fintlabs.adapter.gateway.config.ResourceTtlProperties
import no.fintlabs.adapter.gateway.sync.FullSyncStatus
import no.fintlabs.adapter.gateway.sync.FullSyncStatusStore
import no.fintlabs.adapter.gateway.sync.SyncProgressStore
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.org.OrgStore
import no.novari.fint.core.model.FintModel
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wipes resource types that have had no completed full sync for [ResourceTtlProperties.maxAge].
 * Without a full sync we cannot tell which resources are gone from the source system, so the
 * whole collection goes, including what deltas and event answers delivered since. The adapter's
 * next full sync fills it again.
 *
 * Every hour one sweep is handed to the [EvictionRunner]. Fixed-delay tasks all run on one
 * scheduler thread, the event expiry sweep included, so the sweep never runs there. The runner
 * also runs the evictions after full syncs, so a wipe and an eviction never touch the same
 * collection at the same time. A new sweep is not queued while the previous one is still
 * waiting or running.
 *
 * A collection that has no [FullSyncStatus] yet gets one, and its clock starts at that moment.
 * A resource type whose full sync is running right now is left alone until the sync is done.
 * A sync's first page is written before its progress entry exists, so a sweep that hits that
 * gap drops that page. The next full sync brings it back.
 */
@Service
class ResourceTtlService(
    private val template: MongoTemplate,
    private val orgStore: OrgStore,
    private val fullSyncStatusStore: FullSyncStatusStore,
    private val syncProgressStore: SyncProgressStore,
    private val wipeService: ResourceWipeService,
    private val evictionRunner: EvictionRunner,
    private val providerProperties: ProviderProperties,
    private val properties: ResourceTtlProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sweepQueued = AtomicBoolean(false)

    @Scheduled(fixedDelayString = $$"${fint.provider.resource-ttl.sweep-interval:PT1H}")
    fun expireOldResources() {
        if (!properties.enabled) return
        if (!sweepQueued.compareAndSet(false, true)) {
            log.debug("The previous resource TTL sweep has not finished, skipping this one")
            return
        }

        evictionRunner.submit {
            try {
                sweep()
            } catch (failure: RuntimeException) {
                log.error("Resource TTL sweep failed, trying again on the next sweep", failure)
            } finally {
                sweepQueued.set(false)
            }
        }
    }

    private fun sweep() {
        val now = clock.instant()
        val threshold = now.minus(properties.maxAge)

        existingCoordinates().forEach { fullSyncStatusStore.ensureTracked(it, now) }

        val expired =
            fullSyncStatusStore
                .findAll()
                .filter { it.coordinate.belongsToThisGateway() }
                .filter { it.ttlCountsFrom.isBefore(threshold) }

        expired.forEach { wipe(it) }

        log.debug("Resource TTL sweep found {} resource types without a completed full sync since {}", expired.size, threshold)
    }

    private fun existingCoordinates(): List<ResourceCoordinate> {
        val existing = template.collectionNames

        return orgStore
            .findAll()
            .map { OrgId.from(it.id) }
            .filter { it.belongsTo(providerProperties.orgId) }
            .flatMap { org -> FintModel.refs.map { ResourceCoordinate(org.value, it.domainName, it.packageName, it.resourceName) } }
            .filter { it.toCollectionName() in existing }
    }

    private fun ResourceCoordinate.belongsToThisGateway(): Boolean = OrgId.from(orgId).belongsTo(providerProperties.orgId)

    private fun wipe(status: FullSyncStatus) {
        val collectionName = status.coordinate.toCollectionName()

        if (syncProgressStore.hasRecentSync(status.coordinate)) {
            log.info("Not wiping {}, a full sync is in progress", collectionName)
            return
        }

        when (status.lastCompletedAt) {
            null -> log.info("No full sync of {} has completed since it was first seen at {}, wiping it", collectionName, status.createdAt)
            else -> log.info("No full sync of {} has completed since {}, wiping it", collectionName, status.lastCompletedAt)
        }

        try {
            wipeService.wipe(status.coordinate)
        } catch (failure: RuntimeException) {
            log.error("Wiping {} failed, trying again on the next sweep", collectionName, failure)
        }
    }
}
