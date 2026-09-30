package no.fintlabs.adapter.gateway.config

import no.fintlabs.adapter.gateway.relation.ResourceSelection
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Configuration

/**
 * Shows a query parameter our converters read from one string as that string in the API docs.
 * Without this, springdoc describes the type the converter produces instead.
 */
@Configuration
class SpringDocConfig {
    init {
        SpringDocUtils
            .getConfig()
            .replaceWithClass(ResourceSelection::class.java, String::class.java)
    }
}
