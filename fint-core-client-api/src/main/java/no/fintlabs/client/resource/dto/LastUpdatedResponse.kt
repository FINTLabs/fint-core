package no.fintlabs.client.resource.dto

data class LastUpdatedResponse(
    val lastUpdated: String, // epoch millis, sent as a string because existing clients parse it as one
)
