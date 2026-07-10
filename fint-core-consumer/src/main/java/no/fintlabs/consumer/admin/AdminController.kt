package no.fintlabs.consumer.admin

import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.config.EndpointsConstants
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(EndpointsConstants.ADMIN)
class AdminController(
    private val configuration: ConsumerConfiguration,
) {
    @GetMapping("/health")
    fun healthChecks(): ResponseEntity<*>? =
        TODO("New implementation required, Event based health checks are no longer required. Perhaps lookup Health status in Status Service?")

    @GetMapping("/organisations")
    @Deprecated("")
    fun organisations(): List<String> = emptyList()

    @Deprecated("")
    @GetMapping("/organisations/{orgId:.+}")
    fun getOrganization(
        @PathVariable orgId: String?,
    ): List<String> = emptyList()

    @GetMapping("/assets")
    fun assets(): MutableCollection<String> = hashSetOf(configuration.orgId.value)

    @GetMapping("/caches")
    @Deprecated("")
    fun caches(): MutableMap<String, Int> = HashMap<String, Int>()

    /**
     * Get status for all caches.
     *
     * @return an object where each key is a resource name and each value is an object containing
     * lastUpdated and size for the cache for that resource name
     */
    @GetMapping("/cache/status")
    fun cacheStatus(): Map<String, CacheEntry> =
        TODO("Implement mongoDB lookup of all resources within domainName and packageName. Reponse will be CacheEntry")
//         cacheService.getCachedResourceNames().associateWith { resourceName ->
//             val cache = cacheService.getCache(resourceName)
//             CacheEntry(Date(cache.lastUpdated), cache.size)
//         }

    @PostMapping("/cache/rebuild", "/cache/rebuild/{model}")
    fun rebuildCache(
        @RequestHeader(name = "x-client") client: String?,
        @PathVariable(required = false) model: String?,
    ): Nothing = TODO("Yet to be implemented")

}
