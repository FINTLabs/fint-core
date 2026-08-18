package no.fintlabs.provider.kafka.topic

object TopicNamesConstants {
    const val FINTLABS_NO = "fintlabs-no"
    const val FINT_CORE = "fint-core"

    const val HEARTBEAT_EVENT_NAME = "adapter-health"
    const val ADAPTER_REGISTER_EVENT_NAME = "adapter-register"
    const val ADAPTER_FULL_SYNC_EVENT_NAME = "adapter-full-sync"
    const val ADAPTER_DELTA_SYNC_EVENT_NAME = "adapter-delta-sync"
    const val ADAPTER_DELETE_SYNC_EVENT_NAME = "adapter-delete-sync"

    const val CONSUMER_ERROR_EVENT_NAME = "consumer-error"
    const val PROVIDER_ERROR_EVENT_NAME = "provider-error"
    const val SYNC_STATUS_EVENT_NAME = "sync-status"
}
