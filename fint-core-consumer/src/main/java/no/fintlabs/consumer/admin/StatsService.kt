package no.fintlabs.consumer.admin

import no.fintlabs.consumer.config.ConsumerConfiguration
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.core.model.FintModel
import org.springframework.stereotype.Service
import java.util.Date

@Service
class StatsService(
    private val consumerConfiguration: ConsumerConfiguration,
    private val resourceStore: ResourceStore,
) {
    fun getLastUpdated(resourceCoordinate: ResourceCoordinate): Long =
        resourceStore.getLastUpdated(resourceCoordinate)?.toEpochMilli() ?: 0L

    fun getCacheSize(resourceCoordinate: ResourceCoordinate): Int =
        resourceStore.getCacheSize(resourceCoordinate).toInt()

    fun cacheStatus(): Map<String, CacheEntry> =
        relevantResourceNames().associateWith { resource ->
            val coord =
                ResourceCoordinate(
                    consumerConfiguration.orgId.toString(),
                    consumerConfiguration.domain,
                    consumerConfiguration.packageName,
                    resource,
                )

            CacheEntry(
                resourceStore.getLastUpdated(coord)?.let(Date::from),
                resourceStore.getCacheSize(coord).toInt(),
            )
        }

    private fun relevantResourceNames(): List<String> {
        val componentPath =
            "${consumerConfiguration.domain.lowercase()}/${consumerConfiguration.packageName.lowercase()}"

        return FintModel.resources
            .asSequence()
            .mapNotNull { it.pathIn(componentPath) }
            .map { it.lowercase() }
            .filter { it.startsWith("$componentPath/") }
            .map { it.substringAfterLast("/") }
            .distinct()
            .sorted()
            .toList()
    }
}
