package no.fintlabs.adapter.gateway.storage

import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Lets one transaction at a time change the relations of an org.
 *
 * A batch that stores a source and a batch that stores its target write different documents, so
 * the database lets both commit although neither saw what the other wrote. The source finds no
 * target to give its back-link, the target finds no edge to take it from, and the back-link is
 * lost. Running such transactions one after the other makes each see the one before it.
 */
@Component
class RelationWriteLock(
    private val template: MongoTemplate,
    private val transactions: MongoTransactions,
) {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val prepared = ConcurrentHashMap.newKeySet<String>()

    /**
     * Stores the lock document of [orgId], so a transaction only has to update it. A transaction
     * may not create a collection, so this has to run before one is opened.
     */
    fun prepare(orgId: String) {
        if (!prepared.add(orgId)) return

        template.upsert(byOrg(orgId), Update().setOnInsert("version", 0L), COLLECTION)
    }

    /**
     * Runs [block] in a transaction that no other relation write for [orgIds] runs next to.
     *
     * Two things keep the others out. Inside this process the threads queue up in the order they
     * came. Across processes, for example two pods during a rollout, every transaction starts
     * by writing the lock document of its org, so the database lets only one of them through
     * and [MongoTransactions] runs the other again.
     */
    fun <T> inTransaction(
        orgIds: Collection<String>,
        block: () -> T,
    ): T {
        // Sorted, so two writers that want the same orgs never wait for each other.
        val ordered = orgIds.distinct().sorted()
        val held = ordered.map { locks.computeIfAbsent(it) { ReentrantLock(true) } }

        held.forEach { it.lock() }
        try {
            return transactions.inTransaction {
                // Before anything else, so the transaction reads nothing another writer may still change.
                ordered.forEach { template.updateFirst(byOrg(it), Update().inc("version", 1L), COLLECTION) }
                block()
            }
        } finally {
            held.asReversed().forEach { it.unlock() }
        }
    }

    private fun byOrg(orgId: String): Query = Query.query(Criteria.where("_id").`is`(orgId))

    companion object {
        const val COLLECTION = "relation_write_locks"
    }
}
