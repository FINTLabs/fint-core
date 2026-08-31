package no.fintlabs.consumer.admin

import no.fintlabs.consumer.config.ConsumerConfiguration
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.org.OrgStore
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.core.model.FintModel
import org.springframework.stereotype.Service
import java.util.Date

@Service
class StatsService(
    private val consumerConfiguration: ConsumerConfiguration,
    private val resourceStore: ResourceStore,
    private val orgStore: OrgStore,
) {
    fun getLastUpdated(resourceCoordinate: ResourceCoordinate): Long =
        resourceStore.getLastUpdated(resourceCoordinate)?.toEpochMilli() ?: 0L

    fun getCacheSize(resourceCoordinate: ResourceCoordinate): Int =
        resourceStore.getCacheSize(resourceCoordinate).toInt()

    fun cacheStatus(
        domainName: String,
        packageName: String,
    ): List<OrgCacheStatus> =
        orgStore.findAll().map { org ->
            OrgCacheStatus(
                orgId = org.id,
                caches = cacheStatusFor(org.id, domainName, packageName),
            )
        }

    private fun cacheStatusFor(
        orgId: String,
        domainName: String,
        packageName: String,
    ): Map<String, CacheEntry> =
        FintModel.refsIn(domainName, packageName).associate { ref ->
            val resource = ref.resourceName
            val coord =
                ResourceCoordinate(
                    orgId,
                    consumerConfiguration.domain,
                    consumerConfiguration.packageName,
                    resource,
                )

            resource to
                CacheEntry(
                    resourceStore.getLastUpdated(coord)?.let(Date::from),
                    resourceStore.getCacheSize(coord).toInt(),
                )
        }
}
