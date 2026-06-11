package no.novari.fint.core.synthetic

import com.fasterxml.jackson.databind.ObjectMapper
import no.novari.fint.core.synthetic.config.Datasets
import no.novari.fint.core.synthetic.config.SyntheticProperties
import no.novari.fint.core.synthetic.graph.GraphGenerator
import no.novari.fint.core.synthetic.graph.SyntheticEntity
import no.novari.fint.core.synthetic.output.SyncPageWriter
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Instant

/**
 * The entry point of a run. Merges the built-in dataset ([Datasets.utdanning]) with any YAML
 * overrides, asks [GraphGenerator] for the entity graph, hands each resource to [SyncPageWriter],
 * and writes a `manifest.json` with per-resource link statistics and the generator's findings —
 * the file to feed back when tuning the dataset. Runs once on startup, then the app exits.
 */
@Component
@ConditionalOnProperty(prefix = "synthetic", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class GeneratorRunner(
    private val config: SyntheticProperties,
    private val generator: GraphGenerator,
    private val writer: SyncPageWriter,
    private val objectMapper: ObjectMapper,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(GeneratorRunner::class.java)

    override fun run(args: ApplicationArguments) {
        val config = this.config.copy(resources = Datasets.withDefaults(this.config.resources))

        logger.info(
            "Generating synthetic data (seed={}, scale={}, materializeBackLinks={})",
            config.seed,
            config.scale,
            config.materializeBackLinks,
        )

        val generation = generator.generate(config)
        val resources =
            generation.entities.entries.associate { (descriptor, entities) ->
                val written = writer.write(descriptor, entities, config)
                logger.info(
                    "{}: {} entities across {} page(s) -> {}",
                    written.key,
                    written.entityCount,
                    written.pageCount,
                    written.directory,
                )
                written.key to resourceReport(entities, written.pageCount)
            }

        writeManifest(resources, generation.notes)
        logger.info("Done. Output in {}", Path.of(config.outputDir).toAbsolutePath())
    }

    private fun resourceReport(
        entities: List<SyntheticEntity>,
        pageCount: Int,
    ): Map<String, Any> {
        val links = entities.flatMap { it.links.entries }.groupBy({ it.key }, { it.value.size }).mapValues { it.value.sum() }
        val backLinks =
            entities.flatMap { it.autoRelationLinks.entries }.groupBy({ it.key }, { it.value.size }).mapValues { it.value.sum() }
        return linkedMapOf(
            "count" to entities.size,
            "pages" to pageCount,
            "withoutAnyLinks" to entities.count { it.links.isEmpty() && it.autoRelationLinks.isEmpty() },
            "links" to links,
            "autoRelationBackLinks" to backLinks,
        )
    }

    private fun writeManifest(
        resources: Map<String, Map<String, Any>>,
        notes: List<String>,
    ) {
        val manifest =
            linkedMapOf(
                "generatedAt" to Instant.now().toString(),
                "seed" to config.seed,
                "scale" to config.scale,
                "materializeBackLinks" to config.materializeBackLinks,
                "orgId" to config.orgId,
                "notes" to notes,
                "resources" to resources,
            )
        objectMapper
            .writerWithDefaultPrettyPrinter()
            .writeValue(Path.of(config.outputDir).resolve("manifest.json").toFile(), manifest)
    }
}
