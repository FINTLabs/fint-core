package no.fintlabs.client.resource

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * This controller gives an overview over which endpoints are available
 * For example if you query /utdanning/vurdering, it should give an overview of what resoures
 * are available in that component.
 */
@RestController
@RequestMapping("{domainName}/{packageName}")
class EndpointsController(
    private val endpointsService: EndpointsService,
) {
    @GetMapping
    fun resources(
        @PathVariable domainName: String,
        @PathVariable packageName: String,
    ) = endpointsService.componentOverview(domainName, packageName)
}
