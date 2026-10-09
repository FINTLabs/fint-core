package no.fintlabs.adapter.gateway.security

import io.mockk.every
import io.mockk.mockk
import no.fintlabs.adapter.gateway.config.ProviderProperties
import no.fintlabs.adapter.gateway.register.ContractLookup
import no.fintlabs.adapter.gateway.register.ContractService
import no.fintlabs.adapter.gateway.register.EventCapabilities
import no.fintlabs.adapter.gateway.register.RegisteredContract
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.resourceRefOf
import no.novari.fint.core.model.FintResourceRef
import no.novari.resource.server.authentication.CorePrincipal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt

class AdapterAuthorizationTest {
    private val contractService = mockk<ContractService>()

    private val authorization: AdapterAuthorization =
        AdapterAuthorization(ProviderProperties(orgIdValue = MAIN_ORG), contractService)

    @Nested
    inner class HasOrg {
        @Test
        fun `accepts the main org of this gateway`() {
            assertThat(authorization.hasOrg(adapter(), MAIN_ORG)).isTrue()
        }

        @Test
        fun `accepts a sub-org listed as its own asset`() {
            assertThat(authorization.hasOrg(adapter(assets = "$MAIN_ORG,$SUB_ORG"), SUB_ORG)).isTrue()
        }

        @Test
        fun `rejects an org the adapter has an asset for but this gateway does not serve`() {
            assertThat(authorization.hasOrg(adapter(assets = "$MAIN_ORG,vtfk.no"), "vtfk.no")).isFalse()
        }

        @Test
        fun `rejects an org outside the JWT assets`() {
            assertThat(authorization.hasOrg(adapter(), "other.$MAIN_ORG")).isFalse()
        }

        @Test
        fun `matches regardless of which separator either side uses`() {
            assertThat(authorization.hasOrg(adapter(), "fintlabs-no")).isTrue()
            assertThat(authorization.hasOrg(adapter(), "fintlabs_no")).isTrue()
            assertThat(authorization.hasOrg(adapter(assets = "fintlabs-no"), MAIN_ORG)).isTrue()
        }

        @Test
        fun `rejects a missing orgId`() {
            assertThat(authorization.hasOrg(adapter(), null)).isFalse()
            assertThat(authorization.hasOrg(adapter(), " ")).isFalse()
        }

        @Test
        fun `rejects an authentication that is not an adapter principal`() {
            assertThat(authorization.hasOrg(TestingAuthenticationToken("user", "password"), MAIN_ORG)).isFalse()
        }

        @Test
        fun `rejects a client principal`() {
            assertThat(authorization.hasOrg(adapter(username = "test@client.$MAIN_ORG"), MAIN_ORG)).isFalse()
        }
    }

    @Nested
    inner class IsUsername {
        @Test
        fun `accepts a matching username`() {
            assertThat(authorization.isUsername(adapter(), USERNAME)).isTrue()
        }

        @Test
        fun `rejects a mismatched username`() {
            assertThat(authorization.isUsername(adapter(), "someone@else.no")).isFalse()
        }
    }

    @Nested
    inner class CanSync {
        @Test
        fun `accepts a resource the contract covers`() {
            givenContract(resourceRefOf("utdanning", "elev", "elev"))

            assertThat(authorization.canSync(adapter(), MAIN_ORG, "utdanning", "elev", "elev")).isTrue()
        }

        @Test
        fun `rejects a resource the contract does not cover`() {
            givenContract(resourceRefOf("utdanning", "elev", "elev"))

            assertThat(authorization.canSync(adapter(), MAIN_ORG, "utdanning", "elev", "skoleressurs")).isFalse()
        }

        @Test
        fun `rejects when the adapter has no contract for the org`() {
            every { contractService.lookup(any(), any()) } returns ContractLookup.Absent

            assertThat(authorization.canSync(adapter(), MAIN_ORG, "utdanning", "elev", "elev")).isFalse()
        }

        @Test
        fun `rejects before looking at the contract when the org is not served`() {
            assertThat(authorization.canSync(adapter(), "vtfk.no", "utdanning", "elev", "elev")).isFalse()
        }
    }

    @Nested
    inner class CanAnswerFor {
        @Test
        fun `accepts when a contract exists for the org even without a matching capability`() {
            givenContract()

            assertThat(authorization.canAnswerFor(adapter(), MAIN_ORG)).isTrue()
        }

        @Test
        fun `rejects when the adapter has no contract for the org`() {
            every { contractService.lookup(any(), any()) } returns ContractLookup.Absent

            assertThat(authorization.canAnswerFor(adapter(), MAIN_ORG)).isFalse()
        }
    }

    private fun givenContract(vararg syncResources: FintResourceRef) {
        every { contractService.lookup(any(), any()) } returns
            ContractLookup.Found(RegisteredContract(OrgId.from(MAIN_ORG), syncResources.toSet(), EventCapabilities.NONE))
    }

    private fun adapter(
        assets: String = MAIN_ORG,
        username: String = USERNAME,
        roles: List<String> = listOf("FINT_Adapter_utdanning_elev"),
    ): CorePrincipal {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .claim("cn", username)
                .claim("fintAssetIDs", assets)
                .claim("scope", listOf("fint-adapter"))
                .claim("Roles", roles)
                .build()
        return CorePrincipal(jwt, emptyList())
    }

    private companion object {
        const val MAIN_ORG = "fintlabs.no"
        const val SUB_ORG = "test.fintlabs.no"
        const val USERNAME = "test@adapter.fintlabs.no"
    }
}
