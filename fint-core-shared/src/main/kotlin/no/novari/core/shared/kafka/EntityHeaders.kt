package no.novari.core.shared.kafka

object EntityHeaders {
    const val ORG_ID = "org-id"
    const val DOMAIN_NAME = "domain-name"
    const val PACKAGE_NAME = "package-name"
    const val RESOURCE_NAME = "resource-name"
    const val LAST_MODIFIED = "last-modified"
    const val SYNC_TYPE = "sync-type"
    const val SYNC_CORRELATION_ID = "sync-correlation-id"
    const val SYNC_TOTAL_SIZE = "sync-total-size"

    /**
     * Marks a record that carries no resource and exists only to say a sync happened. A full sync
     * with no resources produces no entity records at all, so without this the buffer would never
     * hear about it and the reset it asks for would never run.
     */
    const val SYNC_MARKER = "sync-marker"
}
