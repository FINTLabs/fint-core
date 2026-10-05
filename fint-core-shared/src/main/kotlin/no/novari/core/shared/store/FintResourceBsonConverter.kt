package no.novari.core.shared.store

import no.novari.core.shared.json.FintJson
import no.novari.fint.core.model.FintResource
import org.bson.Document
import org.bson.types.Binary
import org.springframework.stereotype.Service
import java.security.MessageDigest

/**
 * Converts a `FintResource` into the form MongoDB stores.
 *
 * Links are stored in their id-based form under `_links`; the full href is rebuilt on read from the
 * resource's own metadata, so a change of base URL does not require rewriting stored documents.
 *
 * The storage mapper writes the links sorted (see `SortedLinksSerializer`), so neither the stored
 * form nor the hash depends on the order the adapter sent them in.
 */
@Service
class FintResourceBsonConverter {
    private val mapper = FintJson.storageMapper()

    fun toDocument(resource: FintResource): Document = toStorageForm(resource).document

    /**
     * Serializes the resource once and hands back the document to store together with a hash of
     * the same bytes, so a caller can compare the hash first and only build the document when the
     * content is new. The hash is the SHA-256 of the storage JSON. Any change to the storage
     * mapper or to the model library changes that JSON, and with it every hash, so the next
     * delivery of every resource then counts as a change once.
     */
    fun toStorageForm(resource: FintResource): StorageForm {
        val json = mapper.writeValueAsString(resource)
        return StorageForm(json, contentHash(json))
    }

    private fun contentHash(json: String): Binary = Binary(MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8)))
}

/**
 * A resource ready for storage. [document] is only built when it is read, so a resource whose
 * [contentHash] matches what is stored never pays for the parse.
 */
class StorageForm internal constructor(
    private val json: String,
    val contentHash: Binary,
) {
    val document: Document by lazy { Document.parse(json) }
}
