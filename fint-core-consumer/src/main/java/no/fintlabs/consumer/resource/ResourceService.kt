package no.fintlabs.consumer.resource

import no.fintlabs.cache.CacheService
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.model.resource.FintResources
import no.fintlabs.model.resource.createFintResources
import no.novari.core.shared.model.ResourceRef
import no.novari.fint.model.resource.FintResource
import org.springframework.stereotype.Service

@Service
class ResourceService(
    private val cacheService: CacheService,
    private val consumerConfiguration: ConsumerConfiguration,
) {
    fun getResources(
        resourceRef: ResourceRef,
        size: Int,
        offset: Int,
        sinceTimeStamp: Long,
        filter: String?,
    ): FintResources {
        val cache = cacheService.getCache(resourceRef.resourceName)
        val resources = cache.getList(size.toLong(), offset.toLong(), sinceTimeStamp, filter)
        return createFintResources(
            consumerConfiguration.baseUrl,
            resourceRef.toURI(),
            resources,
            offset,
            size,
            cache.size,
        )
    }

    fun getResourceById(
        resourceName: String,
        idField: String,
        idValue: String,
    ): FintResource? = cacheService.getCache(resourceName).getByIdField(idField, idValue)

    fun getLastUpdated(resourceName: String): Long = cacheService.getCache(resourceName).lastUpdated

    fun getCacheSize(resourceName: String): Int = cacheService.getCache(resourceName).size
}
