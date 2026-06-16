package no.fintlabs.consumer.resource.event

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import com.github.benmanes.caffeine.cache.RemovalCause
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.consumer.config.EventCacheProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit

@Service
class EventStatusCache(
    private val props: EventCacheProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    // How long we keep a response if the Request never arrives (Safety Net)
    private val orphanResponseRetention = Duration.ofHours(12)

    private val responseCache: Cache<String, ResponseFintEvent> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(orphanResponseRetention)
            .build()

    private val requestCache: Cache<String, RequestFintEvent> =
        Caffeine
            .newBuilder()
            .expireAfter(RequestDynamicExpiry())
            .removalListener(::onRequestsRemoved)
            .build()

    private fun onRequestsRemoved(
        key: String?,
        event: RequestFintEvent?,
        cause: RemovalCause,
    ) {
        if (cause == RemovalCause.EXPIRED && key != null) {
            logger.debug("Request expired ($key). Purging associated response.")
            responseCache.invalidate(key)
        }
    }

    fun trackRequest(request: RequestFintEvent) {
        val retention = props.getLifeCycleConfig(request.resourceName).eviction

        if (request.hasRemainingLife(retention)) {
            requestCache.put(request.corrId, request)
        } else {
            logger.info("Request ${request.corrId} arrived expired. Purging response.")
            responseCache.invalidate(request.corrId)
        }
    }

    fun trackResponse(
        key: String,
        response: ResponseFintEvent,
    ) {
        if (response.isWithinRetentionLimit()) {
            responseCache.put(key, response)
        } else {
            logger.warn("Response $key ignored. It is older than the safety retention limit.")
        }
    }

    fun getResponse(corrId: String): ResponseFintEvent? = responseCache.getIfPresent(corrId)

    fun requestExists(corrId: String): Boolean = requestCache.getIfPresent(corrId) != null

    private fun RequestFintEvent.hasRemainingLife(retention: Duration) =
        (clock.millis() - created) < retention.toMillis()

    private fun ResponseFintEvent.isWithinRetentionLimit() =
        (clock.millis() - handledAt) < orphanResponseRetention.toMillis()

    // TODO: Move RequestDynamicExpiry to global scope when converting this project to a multi-module project (same logic in provider-gateway)

    /**
     * Calculates the remaining time a request should live in the cache
     * based on its creation timestamp and the resource configuration.
     */
    inner class RequestDynamicExpiry : Expiry<String, RequestFintEvent> {
        override fun expireAfterCreate(
            key: String,
            value: RequestFintEvent,
            now: Long,
        ): Long =
            props
                .getLifeCycleConfig(value.resourceName)
                .eviction
                .toMillis()
                .minus(clock.millis() - value.created)
                .coerceAtLeast(0)
                .let { TimeUnit.MILLISECONDS.toNanos(it) }

        override fun expireAfterUpdate(
            key: String,
            value: RequestFintEvent,
            currentTime: Long,
            currentDuration: Long,
        ): Long = currentDuration

        override fun expireAfterRead(
            key: String,
            value: RequestFintEvent,
            currentTime: Long,
            currentDuration: Long,
        ): Long = currentDuration
    }

    companion object {
        private val logger = LoggerFactory.getLogger(EventStatusCache::class.java)
    }
}
