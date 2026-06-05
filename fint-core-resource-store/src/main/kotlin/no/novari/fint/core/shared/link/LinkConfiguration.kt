package no.novari.fint.core.shared.link

/**
 * Base URL used to build absolute HATEOAS hrefs for resources and relations. Implemented by the
 * host application (consumer or provider) so the link layer in this shared module does not depend on
 * any service-specific configuration. Java callers see this as `getBaseUrl()`.
 */
interface LinkConfiguration {
    val baseUrl: String
}
