package no.novari.fint.core.shared.cache

import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class CacheService(
    private val mongoTemplate: MongoTemplate,
    private val codec: CacheDocumentCodec,
) {
    private val resourceCaches: MutableMap<String, FintCache> =
        ConcurrentHashMap<String, FintCache>()

    fun getCachedResourceNames(): Set<String> = resourceCaches.keys

    fun getCache(resourceName: String): FintCache {
        val key = resourceName.lowercase()
        return resourceCaches.computeIfAbsent(key) {
            MongoDBFintCache(mongoTemplate, codec, "$COLLECTION_PREFIX$key")
        }
    }

    companion object {
        const val COLLECTION_PREFIX = "cache_"
    }
}
