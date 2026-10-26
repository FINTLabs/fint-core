package no.fintlabs.adapter.gateway.config

import no.novari.core.shared.model.OrgId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

class ProviderPropertiesTest {
    private val properties = ProviderProperties(orgIdValue = "novari.no", extraOrgIdValues = listOf("fintlabs.no"))

    @Test
    fun `serves the deployment org, the extra orgs and their sub-orgs`() {
        assertThat(properties.serves(OrgId.from("novari.no"))).isTrue()
        assertThat(properties.serves(OrgId.from("test.novari.no"))).isTrue()
        assertThat(properties.serves(OrgId.from("fintlabs.no"))).isTrue()
        assertThat(properties.serves(OrgId.from("test.fintlabs.no"))).isTrue()
    }

    @Test
    fun `does not serve any other org`() {
        assertThat(properties.serves(OrgId.from("vtfk.no"))).isFalse()
        assertThat(properties.serves(OrgId.from("labs.no"))).isFalse()
    }

    @Test
    fun `serves only the deployment org without extra orgs`() {
        val bound = bind(mapOf("fint.provider.org-id" to "afk.no"))

        assertThat(bound.extraOrgIds).isEmpty()
        assertThat(bound.serves(OrgId.from("afk.no"))).isTrue()
        assertThat(bound.serves(OrgId.from("fintlabs.no"))).isFalse()
    }

    @Test
    fun `binds the environment variable the novari overlay sets`() {
        val environment =
            SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, // Spring only maps env style names for a source with this name
                mapOf<String, Any>("FINT_PROVIDER_ORGID" to "novari.no", "FINT_PROVIDER_EXTRAORGIDS" to "fintlabs.no,test.no"),
            )

        val bound =
            Binder(
                ConfigurationPropertySources.from(environment).filterNotNull(),
            ).bind("fint.provider", ProviderProperties::class.java).get()

        assertThat(bound.orgId).isEqualTo(OrgId.from("novari.no"))
        assertThat(bound.extraOrgIds).containsExactly(OrgId.from("fintlabs.no"), OrgId.from("test.no"))
    }

    private fun bind(values: Map<String, String>): ProviderProperties =
        Binder(MapConfigurationPropertySource(values)).bind("fint.provider", ProviderProperties::class.java).get()
}
