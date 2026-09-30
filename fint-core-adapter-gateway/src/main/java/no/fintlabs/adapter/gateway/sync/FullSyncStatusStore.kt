package no.fintlabs.adapter.gateway.sync

import no.novari.core.shared.model.ResourceCoordinate
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class FullSyncStatusStore(
    private val template: MongoTemplate,
) {
    /**
     * Records that a full sync of the resource type completed at [completedAt]. A later time is
     * never replaced by an earlier one, so a redelivered sync cannot move it backwards.
     */
    fun recordCompleted(
        coordinate: ResourceCoordinate,
        completedAt: Instant,
    ) {
        val update =
            Update()
                .max("lastCompletedAt", completedAt)
                .setOnInsert("coordinate", coordinate)
                .setOnInsert("createdAt", completedAt)

        template.upsert(byCoordinate(coordinate), update, COLLECTION_NAME)
    }

    /**
     * Creates the status for a resource type the first time it is seen, with no completed full
     * sync. Does nothing when the resource type already has a status.
     */
    fun ensureTracked(
        coordinate: ResourceCoordinate,
        seenAt: Instant,
    ) {
        val update =
            Update()
                .setOnInsert("coordinate", coordinate)
                .setOnInsert("createdAt", seenAt)

        template.upsert(byCoordinate(coordinate), update, COLLECTION_NAME)
    }

    fun findAll(): List<FullSyncStatus> = template.findAll(FullSyncStatus::class.java, COLLECTION_NAME)

    fun delete(coordinate: ResourceCoordinate) {
        template.remove(byCoordinate(coordinate), COLLECTION_NAME)
    }

    private fun byCoordinate(coordinate: ResourceCoordinate): Query = Query.query(Criteria.where("_id").`is`(coordinate.toCollectionName()))

    companion object {
        const val COLLECTION_NAME = "full_sync_status"
    }
}
