package no.fintlabs.adapter.gateway.security

import io.mockk.every
import io.mockk.mockk
import no.fintlabs.adapter.gateway.config.ProviderProperties
import no.fintlabs.adapter.gateway.register.ContractLookup
import no.fintlabs.adapter.gateway.register.ContractService
import no.novari.core.shared.event.EventScope
import no.novari.core.shared.model.OrgId
import no.novari.resource.server.authentication.CorePrincipal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.oauth2.jwt.Jwt

class EventAuthorizationTest {
    private val contractService = mockk<ContractService>()

    private val authorization: EventAuthorization =
        EventAuthorization(AdapterAuthorization(ProviderProperties(orgIdValue = MAIN_ORG), contractService))

    @Nested
    inner class ReadableScopes {
        @Test
        fun `a package the adapter has a role for is read as one scope`() {
            assertThat(authorization.readableScopes(adapter(), "utdanning", "elev", "fravar"))
                .containsExactly(EventScope("utdanning", "elev", "fravar"))
        }

        @Test
        fun `a package the adapter has no role for is refused`() {
            assertThatThrownBy { authorization.readableScopes(adapter(), "utdanning", "vurdering", null) }
                .isInstanceOf(AccessDeniedException::class.java)
                .hasMessage(DenialReason.MISSING_COMPONENT_ROLE.detail)
        }

        @Test
        fun `a domain is read as one scope per package the adapter has a role for`() {
            val adapter =
                adapter(
                    roles =
                        listOf(
                            "FINT_Adapter_utdanning_elev",
                            "FINT_Adapter_utdanning_vurdering",
                            "FINT_Adapter_administrasjon_personal",
                        ),
                )

            assertThat(authorization.readableScopes(adapter, "utdanning", null, null))
                .containsExactlyInAnyOrder(EventScope("utdanning", "elev"), EventScope("utdanning", "vurdering"))
        }

        @Test
        fun `a domain the adapter has no role in is refused`() {
            assertThatThrownBy { authorization.readableScopes(adapter(), "administrasjon", null, null) }
                .isInstanceOf(AccessDeniedException::class.java)
                .hasMessage(DenialReason.MISSING_COMPONENT_ROLE.detail)
        }
    }

    @Nested
    inner class ReadableOrgs {
        @Test
        fun `reads only the orgs the adapter has a contract for`() {
            every { contractService.lookup(USERNAME, MAIN_ORG) } returns ContractLookup.Found(emptySet())
            every { contractService.lookup(USERNAME, SUB_ORG) } returns ContractLookup.Absent

            assertThat(authorization.readableOrgs(adapter(assets = "$MAIN_ORG,$SUB_ORG")))
                .containsExactly(OrgId.from(MAIN_ORG))
        }

        @Test
        fun `leaves out an org this gateway does not serve without asking for its contract`() {
            every { contractService.lookup(USERNAME, MAIN_ORG) } returns ContractLookup.Found(emptySet())

            assertThat(authorization.readableOrgs(adapter(assets = "$MAIN_ORG,vtfk.no")))
                .containsExactly(OrgId.from(MAIN_ORG))
        }

        @Test
        fun `refuses an adapter with no contract for any org and says to register one`() {
            every { contractService.lookup(any(), any()) } returns ContractLookup.Absent

            assertThatThrownBy { authorization.readableOrgs(adapter(assets = "$MAIN_ORG,$SUB_ORG")) }
                .isInstanceOf(AccessDeniedException::class.java)
                .hasMessage(DenialReason.NO_REGISTERED_CONTRACT.detail)
        }

        @Test
        fun `refuses an adapter whose orgs are all served elsewhere`() {
            assertThatThrownBy { authorization.readableOrgs(adapter(assets = "vtfk.no")) }
                .isInstanceOf(AccessDeniedException::class.java)
                .hasMessage(DenialReason.ORG_NOT_SERVED.detail)
        }
    }

    private fun adapter(
        assets: String = MAIN_ORG,
        roles: List<String> = listOf("FINT_Adapter_utdanning_elev"),
    ): CorePrincipal {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .claim("cn", USERNAME)
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
