package no.fintlabs.adapter.gateway.config

import no.novari.core.shared.model.OrgId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

class ProviderPropertiesTest {
    private val properties = ProviderProperties(orgIdValues = listOf("novari.no", "fintlabs.no"))

    @Test
    fun `serves every listed org and its sub-orgs`() {
        assertThat(properties.serves(OrgId.from("novari.no"))).isTrue()
        assertThat(properties.serves(OrgId.from("fintlabs.no"))).isTrue()
        assertThat(properties.serves(OrgId.from("test.fintlabs.no"))).isTrue()
    }

    @Test
    fun `does not serve an org outside the list`() {
        assertThat(properties.serves(OrgId.from("vtfk.no"))).isFalse()
        assertThat(properties.serves(OrgId.from("labs.no"))).isFalse()
    }

    @Test
    fun `the first org is the primary org`() {
        assertThat(properties.primaryOrgId).isEqualTo(OrgId.from("novari.no"))
    }

    @Test
    fun `refuses an empty list`() {
        assertThrows<IllegalArgumentException> { ProviderProperties(orgIdValues = emptyList()) }
    }

    @Test
    fun `binds the comma separated environment variable the novari overlay sets`() {
        val environment =
            SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, // Spring only maps env style names for a source with this name
                mapOf<String, Any>("FINT_PROVIDER_ORGIDS" to "novari.no,fintlabs.no"),
            )

        val bound =
            Binder(
                ConfigurationPropertySources.from(environment).filterNotNull(),
            ).bind("fint.provider", ProviderProperties::class.java).get()

        assertThat(bound.orgIds).containsExactly(OrgId.from("novari.no"), OrgId.from("fintlabs.no"))
    }

    @Test
    fun `binds a single org, which is what FLAIS sets`() {
        val bound = bind(mapOf("fint.provider.org-ids" to "afk.no"))

        assertThat(bound.orgIds).containsExactly(OrgId.from("afk.no"))
    }

    private fun bind(values: Map<String, String>): ProviderProperties =
        Binder(MapConfigurationPropertySource(values)).bind("fint.provider", ProviderProperties::class.java).get()
}
