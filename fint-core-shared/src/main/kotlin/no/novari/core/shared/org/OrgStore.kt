package no.novari.core.shared.org

import no.novari.core.shared.model.OrgId
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class OrgStore(
    private val template: MongoTemplate,
) {
    fun upsert(orgId: String) {
        val now = Instant.now()
        val query = Query.query(Criteria.where("_id").`is`(orgId))
        val update =
            Update()
                .set("updatedAt", now)
                .setOnInsert("createdAt", now)

        template.upsert(query, update, COLLECTION_NAME)
    }

    fun findAll(): List<OrgEntry> = template.find(Query().with(Sort.by("_id")), OrgEntry::class.java, COLLECTION_NAME)

    companion object {
        const val COLLECTION_NAME = "orgs"
    }
}
