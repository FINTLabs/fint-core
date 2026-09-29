package no.fintlabs.adapter.gateway.config

import no.fintlabs.adapter.gateway.admin.FintComponent
import no.novari.fint.core.model.FintResourceRef
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Configuration

/**
 * Shows the types our converters read from one query string as that string in the API docs.
 * Without this, springdoc lists every field of them as a query parameter of its own.
 */
@Configuration
class SpringDocConfig {
    init {
        SpringDocUtils
            .getConfig()
            .addSimpleTypesForParameterObject(FintResourceRef::class.java, FintComponent::class.java)
            .replaceWithClass(FintResourceRef::class.java, String::class.java)
            .replaceWithClass(FintComponent::class.java, String::class.java)
    }
}
