package no.fintlabs.adapter.gateway.storage

import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.BackLinkFill
import no.novari.core.shared.relation.BackLinkStore
import no.novari.core.shared.relation.RelationEdgeChanges
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Keeps the back-links stored on resources in step with the relation edges, inside the
 * transaction that changes the edges. It only exists while `fint.provider.back-links.enabled`
 * is on. Without it the gateway stores edges only, and back-links are put together when a
 * resource is read.
 */
@Component
@ConditionalOnProperty(prefix = "fint.provider.back-links", name = ["enabled"], havingValue = "true")
class BackLinkWriter(
    private val backLinkStore: BackLinkStore,
    private val relationWriteLock: RelationWriteLock,
) {
    /** What a later [inTransaction] needs in place before it opens its transaction. */
    fun prepare(orgId: String) = relationWriteLock.prepare(orgId)

    /** Runs [block] in a transaction that no other relation write for [orgIds] runs next to. */
    fun <T> inTransaction(
        orgIds: Collection<String>,
        block: () -> T,
    ): T = relationWriteLock.inTransaction(orgIds, block)

    /** Gives targets the back-links of the added edges and takes away those of the removed ones. */
    fun patch(
        orgId: String,
        changes: RelationEdgeChanges,
        at: Instant,
    ) = backLinkStore.patch(OrgId.from(orgId), changes, at)

    /** Sets the back-links of resources that edges may already point at. */
    fun fill(
        coordinate: ResourceCoordinate,
        resources: List<BackLinkFill>,
    ) = backLinkStore.fill(coordinate, resources)
}
