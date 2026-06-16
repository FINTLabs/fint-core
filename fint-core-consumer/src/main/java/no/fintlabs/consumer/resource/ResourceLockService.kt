package no.fintlabs.consumer.resource

import com.google.common.util.concurrent.Striped
import org.springframework.stereotype.Service

data class ResourceLockKey(
    val resourceName: String,
    val uniqueIdentifier: String,
)

/**
 * Manages locks for resources, ensuring that only one thread can
 * operate on a given [ResourceLockKey] at a time.
 *
 * Uses [Striped] locking with 256 buckets and weak references,
 * meaning unused locks are automatically garbage collected.
 */
@Service
class ResourceLockService {
    private val striped = Striped.lazyWeakLock(256)

    /**
     * Acquires a lock for [resourceName] + [resourceId], executes [block], then releases the lock.
     *
     * @param resourceName The name of the resource type.
     * @param resourceId The unique identifier for the resource instance.
     * @param block The operation to perform while the lock is held.
     */
    fun withLock(
        resourceName: String,
        resourceId: String,
        block: () -> Unit,
    ) {
        val lock = striped.get(ResourceLockKey(resourceName, resourceId))
        lock.lock()
        try {
            block()
        } finally {
            lock.unlock()
        }
    }
}
