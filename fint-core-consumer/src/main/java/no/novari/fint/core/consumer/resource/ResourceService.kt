package no.novari.fint.core.consumer.resource

import no.novari.fint.core.shared.cache.CacheService
import no.novari.fint.core.shared.link.LinkService
import no.novari.fint.core.shared.resource.FintResources
import no.novari.fint.model.resource.FintResource
import org.springframework.stereotype.Service

@Service
class ResourceService(
    private val linkService: LinkService,
    private val cacheService: CacheService,
) {
    fun getResources(
        resourceName: String,
        size: Int,
        offset: Int,
        sinceTimeStamp: Long,
        filter: String?,
    ): FintResources {
        val cache = cacheService.getCache(resourceName)
        val resources = cache.getList(size.toLong(), offset.toLong(), sinceTimeStamp, filter)
        return linkService.toResources(resourceName, resources, offset, size, cache.size)
    }

    fun getResourceById(
        resourceName: String,
        idField: String,
        idValue: String,
    ): FintResource? = cacheService.getCache(resourceName).getByIdField(idField, idValue)

    fun getLastUpdated(resourceName: String): Long = cacheService.getCache(resourceName).lastUpdated

    fun getCacheSize(resourceName: String): Int = cacheService.getCache(resourceName).size
}
