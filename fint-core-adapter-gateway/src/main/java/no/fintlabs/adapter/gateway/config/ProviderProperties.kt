package no.fintlabs.adapter.gateway.config

import no.novari.core.shared.model.OrgId
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.Name

@ConfigurationProperties(prefix = "fint.provider")
data class ProviderProperties(
    @param:Name("org-id")
    private val orgIdValue: String,
    @param:Name("extra-org-ids")
    private val extraOrgIdValues: List<String> = emptyList(),
) {
    val orgId: OrgId get() = OrgId.from(orgIdValue)

    /** Other main orgs this gateway also serves, such as test orgs that have no deployment of their own. */
    val extraOrgIds: List<OrgId> get() = extraOrgIdValues.map(OrgId::from)

    /** Whether [orgId] is one of the main orgs this gateway serves, or a sub-org of one. */
    fun serves(orgId: OrgId): Boolean = (listOf(this.orgId) + extraOrgIds).any { orgId.belongsTo(it) }
}
