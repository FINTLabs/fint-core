package no.fintlabs.consumer.config

import com.fasterxml.jackson.databind.ObjectMapper
import no.fintlabs.autorelation.model.EntityDescriptor
import no.fintlabs.autorelation.model.RelationBinding
import no.fintlabs.autorelation.model.RelationOperation
import no.fintlabs.autorelation.model.RelationUpdate
import no.novari.fint.model.resource.Link
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import java.util.Date
import kotlin.test.assertTrue

@SpringJUnitConfig(JacksonConfiguration::class)
class JacksonConfigurationTest {
    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `date is using ISO-8601 format`() {
        val date = Date(1672531200000L) // 2023-01-01T00:00:00.000 UTC
        val json = objectMapper.writeValueAsString(date)

        val iso8601Regex = Regex("^\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z\"$")
        assertTrue(iso8601Regex.matches(json), "Expected ISO-8601 format, but got: $json")
    }

    @Test
    fun `can deserialize Kotlin class without throwing`() {
        val relationUpdate = createRelationUpdate()
        val json = objectMapper.writeValueAsString(relationUpdate)

        assertDoesNotThrow("Deserializing RelationUpdate should not throw when KotlinModule is configured") {
            val deserialized = objectMapper.readValue(json, RelationUpdate::class.java)
            assertEquals(relationUpdate.targetIds, deserialized.targetIds)
        }
    }

    private fun createRelationUpdate() =
        RelationUpdate(
            targetEntity =
                EntityDescriptor(
                    domainName = "domain",
                    packageName = "pkg",
                    resourceName = "resourcenaaaame",
                ),
            targetIds = listOf("123"),
            binding =
                RelationBinding(
                    relationName = "relationName",
                    link = Link.with("link"),
                ),
            operation = RelationOperation.ADD,
        )
}
