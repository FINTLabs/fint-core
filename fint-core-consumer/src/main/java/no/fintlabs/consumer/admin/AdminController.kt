package no.fintlabs.consumer.admin

import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.config.EndpointsConstants
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(EndpointsConstants.ADMIN)
class AdminController(
    private val configuration: ConsumerConfiguration,
    private val statsService: StatsService,
) {
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

    @GetMapping("/cache/status")
    fun cacheStatus(
        @PathVariable domainName: String,
        @PathVariable packageName: String,
    ): List<OrgCacheStatus> = statsService.cacheStatus(domainName, packageName)
}
