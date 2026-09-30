package no.fintlabs.client.admin

data class OrgCacheStatus(
    val orgId: String,
    val caches: Map<String, CacheEntry>,
)
