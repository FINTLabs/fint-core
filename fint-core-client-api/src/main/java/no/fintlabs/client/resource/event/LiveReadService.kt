package no.fintlabs.client.resource.event

import no.fint.antlr.FintFilterService
import no.fint.antlr.exception.FilterException
import no.fint.antlr.exception.InvalidSyntaxException
import no.fint.antlr.odata.ODataFilterService
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.client.resource.ListOptions
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.ResourceCoordinate
import org.springframework.stereotype.Service

/**
 * Decides whether a read goes to the adapter instead of the cache, and starts it when it does.
 * A read goes live when the client prefers an asynchronous answer, asks for something a live
 * read can answer, and an adapter for the org has said it reads the resource live, which the
 * org's adapter gateway is asked about. In every other case the caller answers from the cache,
 * as if no preference was sent.
 */
@Service
class LiveReadService(
    private val eventCapabilityClient: EventCapabilityClient,
    private val requestFintEventService: RequestFintEventService,
) {
    private val filterService: FintFilterService = ODataFilterService()

    /**
     * Starts a live read of the resources matching [filter], or returns null when the cache
     * answers. A list read without a filter, or with any of the list options, never goes live.
     */
    fun startByFilter(
        coordinate: ResourceCoordinate,
        filter: String?,
        options: ListOptions,
        prefer: String?,
    ): RequestFintEvent? {
        if (filter == null || options.given || !goesLive(coordinate, prefer)) return null
        if (!filterService.validate(filter)) throw FilterException(InvalidSyntaxException("Invalid \$filter: $filter"))
        return requestFintEventService.createRead(coordinate, ReadRequest.ByFilter(filter))
    }

    /** Starts a live read of the one resource with [idValue] in [idField], or returns null when the cache answers. */
    fun startById(
        coordinate: ResourceCoordinate,
        idField: String,
        idValue: String,
        prefer: String?,
    ): RequestFintEvent? {
        if (!goesLive(coordinate, prefer)) return null
        return requestFintEventService.createRead(coordinate, ReadRequest.ById(idField.lowercase(), idValue))
    }

    private fun goesLive(
        coordinate: ResourceCoordinate,
        prefer: String?,
    ): Boolean {
        if (!PreferHeader.parse(prefer).respondAsync) return false
        val capabilities = eventCapabilityClient.find(OrgId.from(coordinate.orgId)) ?: return false
        return capabilities.canRead(coordinate.toResourceRef())
    }
}
