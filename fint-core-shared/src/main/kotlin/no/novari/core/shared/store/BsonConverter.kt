package no.novari.core.shared.store

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import no.novari.fint.model.resource.FintLinks
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link
import org.bson.Document
import org.springframework.stereotype.Service

abstract class FintLinksBsonMixin {
    @JsonIgnore
    abstract fun getLinks(): Map<String, List<Link>>
}

/**
 * A utility class for converting `FintResource` objects into BSON `Document` instances for MongoDB storage.
 *
 * This class uses a customized `ObjectMapper` from the Jackson library to handle serialization.
 * It includes a specific configuration to:
 * - Exclude null values in the serialization process.
 * - Apply a mixin to handle `FintLinks` serialization by ignoring its `links` property.
 *
 * @constructor Initializes the converter with a provided Jackson `ObjectMapper`.
 * @param objectMapper The base `ObjectMapper` to be used for serialization, with added configurations.
 */
@Service
class FintResourceBsonConverter(
    objectMapper: ObjectMapper,
) {
    private val mapper =
        objectMapper
            .copy()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .addMixIn(FintLinks::class.java, FintLinksBsonMixin::class.java)

    fun toDocument(resource: FintResource): Document = Document.parse(mapper.writeValueAsString(resource))
}
