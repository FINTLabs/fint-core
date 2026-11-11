package no.fintlabs.adapter.gateway.storage

import io.mockk.mockk
import no.novari.core.shared.relation.BackLinkStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.function.Supplier

class BackLinkWriterFlagTest {
    private val context =
        ApplicationContextRunner()
            .withBean(BackLinkStore::class.java, Supplier { mockk<BackLinkStore>() })
            .withBean(RelationWriteLock::class.java, Supplier { mockk<RelationWriteLock>() })
            .withUserConfiguration(BackLinkWriter::class.java)

    @Test
    fun `the writer is left out while the flag is not set`() {
        context.run { assertThat(it).doesNotHaveBean(BackLinkWriter::class.java) }
    }

    @Test
    fun `the writer is left out while the flag is off`() {
        context
            .withPropertyValues("fint.provider.back-links.enabled=false")
            .run { assertThat(it).doesNotHaveBean(BackLinkWriter::class.java) }
    }

    @Test
    fun `the writer is there when the flag is on`() {
        context
            .withPropertyValues("fint.provider.back-links.enabled=true")
            .run { assertThat(it).hasSingleBean(BackLinkWriter::class.java) }
    }
}
