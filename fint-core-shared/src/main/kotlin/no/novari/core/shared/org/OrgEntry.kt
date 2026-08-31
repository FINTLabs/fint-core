package no.novari.core.shared.org

import org.springframework.data.annotation.Id
import java.time.Instant

data class OrgEntry(
    @field:Id
    val id: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)
