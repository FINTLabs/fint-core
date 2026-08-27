package no.fintlabs.provider.event

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.provider.config.ProviderProperties
import no.fintlabs.provider.event.response.ResponseFintEventProducer
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.eventCollectionOrgId
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * Flips events that passed their deadline unanswered from PENDING to EXPIRED, so adapters stop
 * being served them and clients polling the status endpoint see the failure.
 *
 * The collections to sweep are found by listing the event collections themselves: every
 * collection whose org is this provider's org or a sub-org of it is swept, so a new sub-asset
 * is covered from its first event without any separate registry.
 *
 * Expiry stores no response in Mongo, the flip is the entire write. The flip only applies to a
 * PENDING event past its deadline, so when an adapter answers at the same instant, or another
 * replica sweeps the same event, exactly one writer wins. The expired ResponseFintEvent below
 * exists only as a Kafka feed record for external consumers, and only the replica whose flip
 * won publishes it.
 */
@Service
class EventExpiryService(
    private val eventStore: EventStore,
    private val providerProperties: ProviderProperties,
    private val responseFintEventProducer: ResponseFintEventProducer,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = $$"${fint.provider.event.expiry-sweep-interval:PT30S}")
    fun expireOverdueEvents() {
        val now = clock.instant()

        eventStore
            .eventCollections()
            .filter { it.belongsToProvider() }
            .forEach { collectionName -> sweep(collectionName, now) }
    }

    private fun sweep(
        collectionName: String,
        now: Instant,
    ) {
        eventStore.findExpired(collectionName, now).forEach { request ->
            if (eventStore.markExpired(request.corrId, collectionName, now)) {
                logger.info("Event {} expired. Publishing expired response to the feed.", request.corrId)
                responseFintEventProducer.publish(request.toExpiredResponse())
            }
        }
    }

    private fun String.belongsToProvider(): Boolean =
        runCatching { eventCollectionOrgId(this).belongsTo(providerProperties.orgId) }.getOrDefault(false)

    private fun RequestFintEvent.toExpiredResponse(): ResponseFintEvent =
        ResponseFintEvent().apply {
            corrId = this@toExpiredResponse.corrId
            orgId = this@toExpiredResponse.orgId
            handledAt = clock.millis()
            isFailed = true
            errorMessage = "Event expired."
        }
}
