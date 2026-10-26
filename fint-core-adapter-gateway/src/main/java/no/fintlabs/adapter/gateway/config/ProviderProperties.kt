package no.fintlabs.adapter.gateway.config

import no.novari.core.shared.model.OrgId
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.Name

@ConfigurationProperties(prefix = "fint.provider")
data class ProviderProperties(
    @param:Name("org-ids")
    private val orgIdValues: List<String>,
    val baseUrl: String = "https://api.felleskomponent.no",
) {
    init {
        require(orgIdValues.isNotEmpty()) { "fint.provider.org-ids must list at least one org" }
    }

    /** The main orgs this gateway serves. Each one brings its sub-orgs with it. */
    val orgIds: List<OrgId> get() = orgIdValues.map(OrgId::from)

    /** The org this gateway is deployed for. It names the buffer topic. */
    val primaryOrgId: OrgId get() = orgIds.first()

    fun serves(orgId: OrgId): Boolean = orgIds.any { orgId.belongsTo(it) }
}
