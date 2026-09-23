package no.fintlabs.adapter.gateway.storage

/**
 * Runs each eviction on the calling thread, so a test can assert right after the message that
 * completed the sync.
 */
internal class InlineEvictionRunner : EvictionRunner() {
    override fun submit(eviction: () -> Unit) = eviction()
}
