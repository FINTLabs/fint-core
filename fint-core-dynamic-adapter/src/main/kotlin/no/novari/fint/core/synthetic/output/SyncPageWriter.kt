package no.novari.fint.core.synthetic.output

import com.fasterxml.jackson.databind.ObjectMapper
import no.fintlabs.adapter.models.sync.FullSyncPage
import no.fintlabs.adapter.models.sync.SyncPageMetadata
import no.novari.fint.core.shared.autorelation.model.EntityDescriptor
import no.novari.fint.core.synthetic.config.SyntheticProperties
import no.novari.fint.core.synthetic.graph.SyntheticEntity
import no.novari.fint.core.synthetic.graph.key
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Writes one resource's entities as `FullSyncPage` JSON files — the exact payload an adapter
 * would POST to the provider — split into pages of `synthetic.page-size`, one directory per
 * resource under `synthetic.output-dir`.
 */
@Service
class SyncPageWriter(
    private val objectMapper: ObjectMapper,
    private val entryFactory: EntryFactory,
) {
    fun write(
        descriptor: EntityDescriptor,
        entities: List<SyntheticEntity>,
        config: SyntheticProperties,
    ): WrittenResource {
        val key = descriptor.key()
        val directory = Path.of(config.outputDir).resolve(key)
        Files.createDirectories(directory)

        val pages = entities.chunked(config.pageSize)
        pages.forEachIndexed { index, chunk ->
            val page =
                FullSyncPage().apply {
                    metadata =
                        SyncPageMetadata
                            .builder()
                            .orgId(config.orgId)
                            .corrId(UUID.nameUUIDFromBytes("${config.seed}-$key-$index".toByteArray()).toString())
                            .totalSize(entities.size.toLong())
                            .page(index.toLong())
                            .pageSize(chunk.size.toLong())
                            .totalPages(pages.size.toLong())
                            .uriRef("/${descriptor.domainName}/${descriptor.packageName}/${descriptor.resourceName}")
                            .time(System.currentTimeMillis())
                            .build()
                    resources = chunk.map { entryFactory.toEntry(descriptor, it, config) }
                }
            objectMapper.writeValue(directory.resolve(pageFileName(index)).toFile(), page)
        }

        return WrittenResource(key, entities.size, pages.size, directory)
    }

    private fun pageFileName(index: Int): String = "full-sync-page-%04d.json".format(index)
}

data class WrittenResource(
    val key: String,
    val entityCount: Int,
    val pageCount: Int,
    val directory: Path,
)
