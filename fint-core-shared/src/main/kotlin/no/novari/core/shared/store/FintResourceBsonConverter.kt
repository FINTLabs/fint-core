package no.novari.core.shared.store

import no.novari.core.shared.json.FintJson
import no.novari.fint.core.model.FintResource
import org.bson.Document
import org.springframework.stereotype.Service

/**
 * Converts a `FintResource` into a BSON `Document` for MongoDB storage.
 *
 * Links are stored in their id-based form under `_links`; the full href is rebuilt on read from the
 * resource's own metadata, so a change of base URL does not require rewriting stored documents.
 */
@Service
class FintResourceBsonConverter {
    private val mapper = FintJson.storageMapper()

    fun toDocument(resource: FintResource): Document = Document.parse(mapper.writeValueAsString(resource))
}
