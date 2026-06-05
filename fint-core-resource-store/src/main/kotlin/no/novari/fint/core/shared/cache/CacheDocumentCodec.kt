package no.novari.fint.core.shared.cache

import com.fasterxml.jackson.databind.ObjectMapper
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link
import org.bson.Document
import org.springframework.stereotype.Component

/**
 * Translates [FintResource] instances to and from BSON [Document]s used by the Mongo-backed cache.
 *
 * The resource payload is stored as a JSON string in `data` together with its concrete class name
 * in `type` so the original subtype can be reconstructed on read. Identifier values are flattened
 * into the `identifiers` array to support the secondary index used by `getByIdField`.
 *
 * Relation links are the single source of truth in two projections, split by ownership so that
 * concurrent replicas can mutate them with independent atomic Mongo operations:
 *  - `forwardLinks` — the resource's own relations (from the adapter payload). Written by [put].
 *  - `backLinks` — inverse relations maintained by the auto-relation system on behalf of *other*
 *    resources. Mutated only by atomic `$addToSet`/`$pull`-style updates and never touched by [put],
 *    so an entity refresh cannot clobber back-links a different replica just applied.
 *
 * Both are stripped from `data` before serialisation (only the `self` link stays in `data`) and
 * merged back into `_links` on read, de-duplicated by `(key, ref)`. Each entry keeps `relation`/`ref`
 * (the autorelation index) plus the original relation `key` and the full `link` subdocument so reads
 * rebuild `_links` losslessly.
 */
@Component
class CacheDocumentCodec(
    private val objectMapper: ObjectMapper,
) {
    /**
     * The `$set` fields written by [no.novari.fint.core.shared.cache.MongoDBFintCache.put]: everything the entity
     * owns. Deliberately excludes `_id` (carried by the upsert filter) and `backLinks` (owned by the
     * auto-relation system) so a put never overwrites back-links.
     */
    fun toSetDocument(
        resource: FintResource,
        timestamp: Long,
    ): Document {
        val identifiers =
            resource.identifikators
                .filter { it.value?.identifikatorverdi != null }
                .map { (key, value) ->
                    Document()
                        .append(FIELD_IDENTIFIER_KEY, key.lowercase())
                        .append(FIELD_IDENTIFIER_VALUE, value.identifikatorverdi)
                }
        val forwardKeys = resource.links.filterKeys { !it.equals("self", ignoreCase = true) }.keys
        val forwardLinks =
            forwardKeys.flatMap { relation ->
                (resource.links[relation] ?: emptyList()).mapNotNull { linkEntry(relation, it) }
            }
        return Document()
            .append(FIELD_TIMESTAMP, timestamp)
            .append(FIELD_TYPE, resource.javaClass.name)
            .append(FIELD_DATA, serializeWithoutRelations(resource, forwardKeys))
            .append(FIELD_IDENTIFIERS, identifiers)
            .append(FIELD_FORWARD_LINKS, forwardLinks)
            .append(FIELD_HAS_DATA, true)
    }

    /**
     * Builds one link-projection entry `{relation, ref, key, link}`, or `null` when the href cannot
     * be normalised to a [relationRef]. Shared by forward-link serialisation and back-link writes so
     * both arrays carry an identical shape.
     */
    fun linkEntry(
        relation: String,
        link: Link,
    ): Document? =
        relationRef(link.href)?.let { ref ->
            Document()
                .append(FIELD_RELATION_NAME, relation.lowercase())
                .append(FIELD_RELATION_REF, ref)
                .append(FIELD_RELATION_KEY, relation)
                .append(FIELD_RELATION_LINK, objectMapper.convertValue(link, Document::class.java))
        }

    /**
     * Serialises [resource] with its relation links removed (self retained). The relation entries
     * are stripped, the payload serialised, then the entries restored so the live resource object
     * is left untouched.
     */
    private fun serializeWithoutRelations(
        resource: FintResource,
        relationKeys: Set<String>,
    ): String {
        val removed = relationKeys.associateWith { resource.links.remove(it) }
        return try {
            objectMapper.writeValueAsString(resource)
        } finally {
            removed.forEach { (key, links) -> if (links != null) resource.links[key] = links }
        }
    }

    fun fromDocument(doc: Document): FintResource {
        val type = doc.getString(FIELD_TYPE)
        val data = doc.getString(FIELD_DATA)
        val cls = Class.forName(type).asSubclass(FintResource::class.java)
        val resource = objectMapper.readValue(data, cls)
        val seen = HashSet<String>()
        linkEntries(doc, FIELD_FORWARD_LINKS).forEach { entry ->
            val key = entry.getString(FIELD_RELATION_KEY) ?: return@forEach
            val ref = entry.getString(FIELD_RELATION_REF) ?: return@forEach
            if (!seen.add("${key.lowercase()}|$ref")) return@forEach
            val link = objectMapper.convertValue(entry[FIELD_RELATION_LINK], Link::class.java) ?: return@forEach
            resource.addLink(key, link)
        }
        return resource
    }

    /**
     * Merge externally-stored back-link rows (from the `backlinks` collection) into [resource]'s
     * `_links`, de-duplicated against the links already present (the forward links) by `(key, ref)`.
     * Replaces the old embedded-`backLinks` merge now that back-links live in their own collection.
     */
    fun mergeBackLinks(
        resource: FintResource,
        rows: List<Document>,
    ) {
        if (rows.isEmpty()) return
        val seen = HashSet<String>()
        resource.links.forEach { (key, links) ->
            links.forEach { link -> relationRef(link.href)?.let { seen.add("${key.lowercase()}|$it") } }
        }
        rows.forEach { row ->
            val key = row.getString(FIELD_RELATION_KEY) ?: return@forEach
            val ref = row.getString(FIELD_RELATION_REF) ?: return@forEach
            if (!seen.add("${key.lowercase()}|$ref")) return@forEach
            val link = objectMapper.convertValue(row[FIELD_RELATION_LINK], Link::class.java) ?: return@forEach
            resource.addLink(key, link)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun linkEntries(
        doc: Document,
        field: String,
    ): List<Document> = doc[field] as? List<Document> ?: emptyList()

    fun timestamp(doc: Document): Long = doc.getLong(FIELD_TIMESTAMP)

    fun resourceId(doc: Document): String = doc.getString(FIELD_ID)

    companion object {
        const val FIELD_ID = "_id"
        const val FIELD_TIMESTAMP = "timestamp"
        const val FIELD_TYPE = "type"
        const val FIELD_DATA = "data"
        const val FIELD_HAS_DATA = "hasData"
        const val FIELD_IDENTIFIERS = "identifiers"
        const val FIELD_IDENTIFIER_KEY = "key"
        const val FIELD_IDENTIFIER_VALUE = "value"
        const val FIELD_FORWARD_LINKS = "forwardLinks"
        const val FIELD_BACK_LINKS = "backLinks"
        const val FIELD_RELATION_NAME = "relation"
        const val FIELD_RELATION_REF = "ref"
        const val FIELD_RELATION_KEY = "key"
        const val FIELD_RELATION_LINK = "link"

        /**
         * Normalises a link href to the `idField/idValue` form used to identify the resource it
         * points to, lowercasing the id field. Both the stored projection and the lookup value
         * must go through this so a query matches regardless of whether the href is absolute,
         * relative, or templated.
         */
        fun relationRef(href: String?): String? {
            val segments = (href ?: return null).split("/").filter { it.isNotBlank() }
            if (segments.size < 2) return null
            return "${segments[segments.size - 2].lowercase()}/${segments.last()}"
        }
    }
}
