package no.novari.core.shared.relation

import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.IdentifierRef
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

// data class RelationEdge(
//    @field:Id
//    val id: ObjectId,
//    val sourceIdField: String, // For example feidenavn for an Elev
//    val sourceId: String, // For example 123
//    val backlinks: List<BackLink>, // This is the links we have to set.
// )
//
// data class BackLink(
//    val sourceIdField: String, // For example feidenavn for an Elev
//    val sourceId: String, // For example 123
// )

data class RelationEndpoint(
    val coordinate: ResourceCoordinate,
    val identifier: IdentifierRef, // exactly one field/value pair
    val relationName: String, // relation exposed from this endpoint
)

@Document("relation_edges")
data class StoredRelation(
    val source: RelationEndpoint,
    val target: RelationEndpoint,
    val lastModified: Instant = Instant.now(),
)

/*
For Elevforhold -> Elev:

 ```text
   source:
     coordinate: fintlabs.no / utdanning / elev / elevforhold
     identifier: systemid / EF-123
     relationName: elev

   target:
     coordinate: fintlabs.no / utdanning / elev / elev
     identifier: elevnummer / E-456
     relationName: elevforhold
 ```
 */
