package no.fintlabs.consumer.config

import no.novari.core.shared.json.FintJson
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.servlet.HandlerMapping
import tools.jackson.databind.json.JsonMapper

/**
 * The consumer's primary mapper speaks the response contract: outbound `_links` as full hrefs
 * with a regenerated `self`. A common resource renders against the component of the request
 * being served, read from the `{domainName}/{packageName}` path variables and never from
 * configuration, since one deployment serves every component. Storage-form conversion never
 * goes through this bean; those sites build `FintJson.storageMapper()` themselves.
 */
@Configuration
open class JacksonConfiguration {
    @Bean
    @Primary
    open fun jsonMapper(consumerConfiguration: ConsumerConfiguration): JsonMapper =
        FintJson.responseMapper(consumerConfiguration.baseUrl, ::requestComponent)
}

private fun requestComponent(): String? {
    val attributes = RequestContextHolder.getRequestAttributes() ?: return null
    val variables =
        attributes.getAttribute(
            HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
            RequestAttributes.SCOPE_REQUEST,
        ) as? Map<*, *> ?: return null
    val domainName = variables["domainName"] as? String ?: return null
    val packageName = variables["packageName"] as? String ?: return null

    return "$domainName/$packageName".lowercase()
}
