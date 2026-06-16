package no.fintlabs.autorelation.buffer

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import com.github.benmanes.caffeine.cache.RemovalCause
import no.fintlabs.autorelation.MetricService
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.novari.fint.model.resource.Link
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

data class TimestampedLinks(
    val createdAt: Instant,
    val links: MutableList<Link>,
)

@Component
class UnresolvedRelationCache(
    consumerConfiguration: ConsumerConfiguration,
    private val metricService: MetricService,
) {
    private val cache: Cache<RelationKey, TimestampedLinks> =
        buildRelationCache(consumerConfiguration.autorelation.buffer.ttl) { key, value, cause ->
            if (cause == RemovalCause.EXPIRED && key != null && value != null) {
                metricService.incrementBufferExpired(
                    key.resource,
                    key.relation,
                    value.links.size,
                )
            }
        }

    init {
        metricService.registerBufferSizeGauge { cache.estimatedSize() }
    }

    fun takeRelations(
        resourceName: String,
        resourceId: String,
        relationName: String,
    ): List<Link> =
        cache
            .asMap()
            .remove(RelationKey(resourceName, resourceId, relationName))
            ?.links
            ?.toList()
            .orEmpty()

    fun registerRelation(
        resourceName: String,
        resourceId: String,
        relationName: String,
        relationLink: Link,
        createdAt: Long,
    ) = RelationKey(resourceName, resourceId, relationName).let { key ->
        cache.asMap().compute(key) { _, existing ->
            if (existing != null) {
                existing.links.add(relationLink)
                existing
            } else {
                TimestampedLinks(Instant.ofEpochMilli(createdAt), mutableListOf(relationLink))
            }
        }
    }

    fun removeRelation(
        resourceName: String,
        resourceId: String,
        relationName: String,
        relationLink: Link,
    ) = RelationKey(resourceName, resourceId, relationName).let { key ->
        cache.asMap().computeIfPresent(key) { _, existing ->
            existing.links.remove(relationLink)
            if (existing.links.isEmpty()) {
                null
            } else {
                existing
            }
        }
    }

    // used for testing
    fun cleanUp() = cache.cleanUp()
}

private fun buildRelationCache(
    ttl: Duration,
    onRemoval: (RelationKey?, TimestampedLinks?, RemovalCause) -> Unit,
): Cache<RelationKey, TimestampedLinks> =
    Caffeine
        .newBuilder()
        .expireAfter(
            object : Expiry<RelationKey, TimestampedLinks> {
                override fun expireAfterCreate(
                    key: RelationKey,
                    value: TimestampedLinks,
                    currentTime: Long,
                ): Long {
                    val remaining = ttl.minus(Duration.between(value.createdAt, Instant.now()))
                    return if (remaining.isNegative) 0 else TimeUnit.MILLISECONDS.toNanos(remaining.toMillis())
                }

                override fun expireAfterUpdate(
                    key: RelationKey,
                    value: TimestampedLinks,
                    currentTime: Long,
                    currentDuration: Long,
                ): Long = currentDuration

                override fun expireAfterRead(
                    key: RelationKey,
                    value: TimestampedLinks,
                    currentTime: Long,
                    currentDuration: Long,
                ): Long = currentDuration
            },
        ).removalListener(onRemoval)
        .build()
