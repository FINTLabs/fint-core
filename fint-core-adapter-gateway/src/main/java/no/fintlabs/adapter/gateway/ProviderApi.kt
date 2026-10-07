package no.fintlabs.adapter.gateway

/**
 * Every endpoint an adapter calls lives under this prefix, and so do the api docs and the
 * actuator (see `application.yaml`). The ingress routes this prefix and nothing else, so a
 * controller outside it, like `/internal`, is reachable inside the cluster only.
 */
object ProviderApi {
    const val PREFIX = "/provider"
}
