package no.novari.core.shared.event

import no.novari.core.shared.model.OrgId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Service

/**
 * One document per org in the `event_capabilities` collection. The gateway writes a whole
 * document on every registration and on start, client-api only reads.
 */
@Service
class EventCapabilityStore(
    private val template: MongoTemplate,
) {
    fun save(capabilities: OrgEventCapabilities) = template.save(capabilities, COLLECTION_NAME)

    fun find(orgId: OrgId): OrgEventCapabilities? = template.findById(orgId.value, OrgEventCapabilities::class.java, COLLECTION_NAME)

    companion object {
        const val COLLECTION_NAME = "event_capabilities"
    }
}
