package no.novari.fint.core.synthetic

import com.fasterxml.jackson.databind.ObjectMapper
import no.fintlabs.adapter.models.sync.FullSyncPage
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.novari.fint.core.shared.autorelation.model.EntityDescriptor
import no.novari.fint.core.shared.autorelation.model.toEntityDescriptor
import no.novari.fint.core.synthetic.config.Datasets
import no.novari.fint.core.synthetic.config.RelationSpec
import no.novari.fint.core.synthetic.config.ResourceSpec
import no.novari.fint.core.synthetic.config.SyntheticProperties
import no.novari.fint.core.synthetic.graph.GraphGenerator
import no.novari.fint.core.synthetic.graph.SyntheticEntity
import no.novari.fint.core.synthetic.output.EntryFactory
import no.novari.fint.core.synthetic.output.SyncPageWriter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(properties = ["synthetic.enabled=false"])
class SyntheticDataGenerationTest
    @Autowired
    constructor(
        private val generator: GraphGenerator,
        private val writer: SyncPageWriter,
        private val entryFactory: EntryFactory,
        private val objectMapper: ObjectMapper,
    ) {
        private val elev = "utdanning-elev-elev".toEntityDescriptor()
        private val elevforhold = "utdanning-elev-elevforhold".toEntityDescriptor()

        private fun config(
            materializeBackLinks: Boolean = false,
            cardinality: String = "1",
            outputDir: String = "build/test-output",
            scale: Double = 1.0,
        ) = SyntheticProperties(
            seed = 42,
            scale = scale,
            pageSize = 7,
            outputDir = outputDir,
            materializeBackLinks = materializeBackLinks,
            resources =
                linkedMapOf(
                    "utdanning-elev-elev" to ResourceSpec(count = 10),
                    "utdanning-elev-elevforhold" to
                        ResourceSpec(
                            count = 15,
                            relations = mapOf("elev" to RelationSpec(cardinality = cardinality)),
                        ),
                ),
        )

        @Test
        fun `generates configured counts`() {
            val graph = generator.generate(config()).entities

            assertEquals(10, graph.getValue(elev).size)
            assertEquals(15, graph.getValue(elevforhold).size)
        }

        @Test
        fun `scale multiplies counts but not fixed resources`() {
            val graph =
                generator
                    .generate(
                        SyntheticProperties(
                            scale = 0.5,
                            resources =
                                linkedMapOf(
                                    "utdanning-elev-elev" to ResourceSpec(count = 10),
                                    "utdanning-kodeverk-skolear" to ResourceSpec(count = 41, fixed = true),
                                ),
                        ),
                    ).entities

            assertEquals(5, graph.getValue(elev).size)
            assertEquals(41, graph.getValue("utdanning-kodeverk-skolear".toEntityDescriptor()).size)
        }

        @Test
        fun `count zero excludes the resource`() {
            val graph =
                generator
                    .generate(
                        SyntheticProperties(
                            resources =
                                linkedMapOf(
                                    "utdanning-elev-elev" to ResourceSpec(count = 10),
                                    "utdanning-elev-elevforhold" to ResourceSpec(count = 0),
                                ),
                        ),
                    ).entities

            assertEquals(setOf(elev), graph.keys)
        }

        @Test
        fun `config overrides merge over the built-in dataset per key`() {
            val merged = Datasets.withDefaults(mapOf("utdanning-elev-elev" to ResourceSpec(count = 5)))

            assertEquals(5, merged.getValue("utdanning-elev-elev").count)
            assertEquals(Datasets.utdanning.size, merged.size)
            assertEquals(Datasets.utdanning.getValue("utdanning-elev-elevforhold"), merged.getValue("utdanning-elev-elevforhold"))
        }

        @Test
        fun `same seed produces identical graphs`() {
            val first = snapshot(generator.generate(config()).entities)
            val second = snapshot(generator.generate(config()).entities)

            assertEquals(first, second)
        }

        @Test
        fun `forward links use the bare-id form autorelation can resolve`() {
            val graph = generator.generate(config()).entities

            graph.getValue(elevforhold).forEach { entity ->
                val entry = entryFactory.toEntry(elevforhold, entity, config())
                val body = entry.body()
                val systemId = (body["systemId"] as Map<*, *>)["identifikatorverdi"]

                assertEquals(entity.id, entry.identifier)
                assertEquals(entity.id, systemId)
                entry.links("elev").orEmpty().forEach { href ->
                    assertTrue(href.matches(Regex("systemid/elev-\\d+")), "unexpected href: $href")
                }
            }
        }

        @Test
        fun `autorelation-covered back-links are excluded when not materialized`() {
            val graph = generator.generate(config(materializeBackLinks = false)).entities

            assertTrue(graph.getValue(elev).any { it.autoRelationLinks.containsKey("elevforhold") })
            graph.getValue(elev).forEach { entity ->
                assertNull(entryFactory.toEntry(elev, entity, config(materializeBackLinks = false)).links("elevforhold"))
            }
        }

        @Test
        fun `autorelation-covered back-links are emitted when materialized`() {
            val graph = generator.generate(config(materializeBackLinks = true)).entities

            val materialized = config(materializeBackLinks = true)
            val forward =
                graph
                    .getValue(elevforhold)
                    .flatMap { entity ->
                        entryFactory
                            .toEntry(elevforhold, entity, materialized)
                            .links("elev")
                            .orEmpty()
                            .map { it to "systemid/${entity.id}" }
                    }.groupBy({ it.first }, { it.second })

            graph.getValue(elev).forEach { entity ->
                val backLinks = entryFactory.toEntry(elev, entity, materialized).links("elevforhold").orEmpty()
                assertEquals(forward["systemid/${entity.id}"].orEmpty().toSet(), backLinks.toSet())
            }
        }

        @Test
        fun `instancio populates fields deterministically without touching identity or links`() {
            val graph = generator.generate(config()).entities
            val entity = graph.getValue(elevforhold).first()

            val first = entryFactory.toEntry(elevforhold, entity, config()).body()
            val second = entryFactory.toEntry(elevforhold, entity, config()).body()

            assertEquals(first, second, "same seed must produce identical populated fields")
            assertTrue(first.keys.size > 2, "expected populated fields beyond systemId and _links, got ${first.keys}")
            assertEquals(mapOf("identifikatorverdi" to entity.id), first["systemId"])

            val lean = entryFactory.toEntry(elevforhold, entity, config().copy(populateFields = false)).body()
            assertEquals(setOf("systemId", "_links"), lean.keys.map { it.toString() }.toSet())
        }

        @Test
        fun `required relations are wired automatically from the metamodel`() {
            val graph =
                generator
                    .generate(
                        SyntheticProperties(
                            resources =
                                linkedMapOf(
                                    "utdanning-elev-elev" to ResourceSpec(count = 10),
                                    "utdanning-elev-person" to ResourceSpec(count = 10),
                                ),
                        ),
                    ).entities

            graph.getValue(elev).forEach { entity ->
                assertEquals(1, entity.links["person"]?.size, "elev must auto-link its required person")
            }

            val person = "utdanning-elev-person".toEntityDescriptor()
            graph.getValue(person).forEach { entity ->
                assertTrue(
                    entity.links["elev"].orEmpty().size <= 1,
                    "person.elev is to-one; ${entity.id} must not collect multiple elev links",
                )
            }
            val forwardPairs =
                graph
                    .getValue(elev)
                    .flatMap { e -> e.links["person"].orEmpty().map { it to e.id } }
                    .toSet()
            val inversePairs =
                graph
                    .getValue(person)
                    .flatMap { p -> p.links["elev"].orEmpty().map { p.id to it } }
                    .toSet()
            assertEquals(forwardPairs, inversePairs, "person.elev must mirror elev.person, never be wired independently")
        }

        @Test
        fun `autorelation-managed inverse relations are not wired independently`() {
            val graph = generator.generate(config()).entities

            graph.getValue(elev).forEach { entity ->
                assertNull(entity.links["elevforhold"], "elev.elevforhold is autorelation's job, not a forward link")
            }
            graph.getValue(elevforhold).forEach { entity ->
                assertEquals(1, entity.links["elev"]?.size)
            }
        }

        @Test
        fun `cardinality ranges are respected with distinct targets`() {
            val graph = generator.generate(config(cardinality = "1..3")).entities

            graph.getValue(elevforhold).forEach { entity ->
                val targets = entity.links.getValue("elev")
                assertTrue(targets.size in 1..3, "expected 1..3 targets, got ${targets.size}")
            }
        }

        @Test
        fun `written pages round-trip as FullSyncPage`(
            @TempDir tempDir: Path,
        ) {
            val config = config(outputDir = tempDir.toString())
            val graph = generator.generate(config).entities
            val written = writer.write(elevforhold, graph.getValue(elevforhold), config)

            assertEquals(15, written.entityCount)
            assertEquals(3, written.pageCount)

            val files = written.directory.listDirectoryEntries("*.json").sorted()
            assertEquals(3, files.size)

            val pages = files.map { objectMapper.readValue(it.toFile(), FullSyncPage::class.java) }
            pages.forEach { page ->
                assertEquals(15, page.metadata.totalSize)
                assertEquals(3, page.metadata.totalPages)
                assertEquals(config.orgId, page.metadata.orgId)
                assertEquals("/utdanning/elev/elevforhold", page.metadata.uriRef)
            }
            assertEquals(15, pages.sumOf { it.resources.size })
            assertEquals((0L..2L).toList(), pages.map { it.metadata.page }.sorted())
        }

        @Test
        fun `unknown resources and relations fail loudly`() {
            assertFailsWith<IllegalStateException> {
                generator.generate(
                    SyntheticProperties(resources = mapOf("utdanning-elev-finnesikke" to ResourceSpec(count = 1))),
                )
            }
            assertFailsWith<IllegalStateException> {
                generator.generate(
                    SyntheticProperties(
                        resources =
                            mapOf(
                                "utdanning-elev-elevforhold" to
                                    ResourceSpec(count = 1, relations = mapOf("finnesikke" to RelationSpec())),
                            ),
                    ),
                )
            }
        }

        private fun snapshot(graph: Map<EntityDescriptor, List<SyntheticEntity>>) =
            graph.mapValues { (_, entities) ->
                entities.map { Triple(it.id, it.links.toMap(), it.autoRelationLinks.toMap()) }
            }

        private fun SyncPageEntry.body(): Map<*, *> = resource as Map<*, *>

        private fun SyncPageEntry.links(relation: String): List<String>? =
            (body()["_links"] as? Map<*, *>)
                ?.get(relation)
                ?.let { links -> (links as List<*>).map { (it as Map<*, *>)["href"] as String } }
    }
