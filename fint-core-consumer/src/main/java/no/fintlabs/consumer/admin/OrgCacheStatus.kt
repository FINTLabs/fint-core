package no.fintlabs.consumer.admin

data class OrgCacheStatus(
    val orgId: String,
    val caches: Map<String, CacheEntry>,
)
