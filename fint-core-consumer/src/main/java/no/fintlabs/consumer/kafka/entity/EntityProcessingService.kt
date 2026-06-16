package no.fintlabs.consumer.kafka.entity

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import no.fintlabs.autorelation.AutoRelationService
import no.fintlabs.autorelation.MetricService
import no.fintlabs.autorelation.RelationEventService
import no.fintlabs.cache.CacheService
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.kafka.sync.SyncTrackerService
import no.fintlabs.consumer.links.LinkService
import no.fintlabs.consumer.resource.ResourceLockService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class EntityProcessingService(
    private val linkService: LinkService,
    private val cacheService: CacheService,
    private val autoRelationService: AutoRelationService,
    private val relationEventService: RelationEventService,
    private val consumerConfiguration: ConsumerConfiguration,
    private val syncTrackerService: SyncTrackerService,
    private val meterRegistry: MeterRegistry,
    private val resourceLockService: ResourceLockService,
    private val metricService: MetricService,
) {
    fun processEntityConsumerRecord(record: EntityConsumerRecord) {
        val resourceName = record.resourceName
        timed(resourceName, "record.process.total") {
            resourceLockService.withLock(resourceName, record.key) {
                if (record.resource == null) {
                    timed(resourceName, "record.deletePath") {
                        deleteEntity(record)
                    }
                } else {
                    timed(resourceName, "record.addPath") {
                        addToCache(record)
                    }
                }

                if (record.type != null) {
                    timed(resourceName, "sync.processRecordMetadata") {
                        syncTrackerService.processRecordMetadata(record)
                    }
                }
            }
        }
    }

    private fun deleteEntity(record: EntityConsumerRecord) {
        val cache =
            timed(record.resourceName, "cache.getCache") {
                cacheService.getCache(record.resourceName)
            }

        timed(record.resourceName, "cache.get") {
            cache.get(record.key)
        }?.let {
            if (consumerConfiguration.autorelation.enabled) {
                timed(record.resourceName, "relation.removeRelations") {
                    relationEventService.removeRelations(record.resourceName, record.key, it)
                }
            }
        }

        timed(record.resourceName, "cache.remove") {
            cache.remove(record.key, record.timestamp)
        }
    }

    private fun addToCache(record: EntityConsumerRecord) {
        val resource = requireNotNull(record.resource)
        val cache =
            timed(record.resourceName, "cache.getCache") {
                cacheService.getCache(record.resourceName)
            }

        if (consumerConfiguration.autorelation.enabled) {
            timed(record.resourceName, "autorelation.reconcileLinks") {
                autoRelationService.reconcileLinks(record.resourceName, record.key, resource)
            }
        }

        timed(record.resourceName, "links.map") {
            linkService.mapLinks(record.resourceName, resource)
        }
        val accepted =
            timed(record.resourceName, "cache.put") {
                cache.put(record.key, resource, record.timestamp)
            }
        if (!accepted) {
            metricService.incrementCachePutRejectedOlderTimestamp(record.resourceName)
        }
    }

    private fun <T> timed(
        resourceName: String,
        operation: String,
        supplier: () -> T,
    ): T {
        val sample = Timer.start(meterRegistry)
        var status = "success"
        return try {
            supplier.invoke()
        } catch (runtimeException: RuntimeException) {
            status = "error"
            logger.error(
                "Processing component failed: operation={}, resource={}, org={}, status={}",
                operation,
                safeResourceName(resourceName),
                consumerConfiguration.orgId.value,
                status,
                runtimeException,
            )
            throw runtimeException
        } finally {
            val duration = Duration.ofNanos(sample.stop(timer(resourceName, operation, status)))
            if (duration > SLOW_COMPONENT_THRESHOLD) {
                logger.warn(
                    "Slow processing component detected: operation={}, durationMs={}, resource={}, org={}, status={}",
                    operation,
                    duration.toMillis(),
                    safeResourceName(resourceName),
                    consumerConfiguration.orgId.value,
                    status,
                )
            }
        }
    }

    private fun timer(
        resourceName: String,
        operation: String,
        status: String,
    ): Timer =
        Timer
            .builder("core.consumer.processing")
            .description("Duration of internal processing steps for Kafka entity records")
            .tag("org", consumerConfiguration.orgId.value)
            .tag("resource", safeResourceName(resourceName))
            .tag("operation", operation)
            .tag("status", status)
            .register(meterRegistry)

    private fun safeResourceName(resourceName: String?): String = resourceName?.takeIf { it.isNotBlank() } ?: "unknown"

    companion object {
        private val logger = LoggerFactory.getLogger(EntityProcessingService::class.java)
        private val SLOW_COMPONENT_THRESHOLD = Duration.ofSeconds(10)
    }
}
