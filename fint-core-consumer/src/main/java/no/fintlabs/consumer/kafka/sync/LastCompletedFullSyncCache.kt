package no.fintlabs.consumer.kafka.sync

import org.springframework.stereotype.Service

@Service
class LastCompletedFullSyncCache {
    private val cache = mutableMapOf<String, Long>()

    fun registerTimestamp(
        resourceName: String,
        timestamp: Long,
    ) {
        val oldTimestamp: Long = cache[resourceName] ?: 0L

        if (timestamp > oldTimestamp) cache[resourceName] = timestamp
    }

    fun getLatestFromResource(resourceName: String): Long = cache.getOrElse(resourceName) { 0L }
}
