package no.fintlabs.adapter.gateway.security

import io.mockk.every
import io.mockk.mockk
import no.fintlabs.adapter.gateway.config.ProviderProperties
import no.fintlabs.adapter.gateway.register.ContractLookup
import no.fintlabs.adapter.gateway.register.ContractService
import no.fintlabs.adapter.gateway.register.EventCapabilities
import no.fintlabs.adapter.gateway.register.RegisteredContract
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.resourceRefOf
import no.novari.resource.server.authentication.CorePrincipal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt

class EventAuthorizationTest {
    private val contractService = mockk<ContractService>()

    private val authorization: EventAuthorization =
        EventAuthorization(AdapterAuthorization(ProviderProperties(orgIdValues = listOf(MAIN_ORG)), contractService))

    @AfterEach
    fun clearSecurityContext() = SecurityContextHolder.clearContext()

    @Nested
    inner class ReadableOrgs {
        @Test
        fun `an org with a contract is served what the contract lists`() {
            every { contractService.lookup(USERNAME, MAIN_ORG) } returns ContractLookup.Found(contractFor(MAIN_ORG, READS_ELEV))

            assertThat(authorization.readableOrgs(adapter()))
                .containsExactlyEntriesOf(mapOf(OrgId.from(MAIN_ORG) to READS_ELEV))
        }

        @Test
        fun `an org without a contract is served everything except READ`() {
            every { contractService.lookup(USERNAME, MAIN_ORG) } returns ContractLookup.Found(contractFor(MAIN_ORG, READS_ELEV))
            every { contractService.lookup(USERNAME, SUB_ORG) } returns ContractLookup.Absent

            assertThat(authorization.readableOrgs(adapter(assets = "$MAIN_ORG,$SUB_ORG")))
                .containsExactlyInAnyOrderEntriesOf(
                    mapOf(OrgId.from(MAIN_ORG) to READS_ELEV, OrgId.from(SUB_ORG) to EventCapabilities.NONE),
                )
        }

        @Test
        fun `leaves out an org this gateway does not serve without asking for its contract`() {
            every { contractService.lookup(USERNAME, MAIN_ORG) } returns ContractLookup.Absent

            assertThat(authorization.readableOrgs(adapter(assets = "$MAIN_ORG,vtfk.no")).keys)
                .containsExactly(OrgId.from(MAIN_ORG))
        }

        @Test
        fun `refuses an adapter whose orgs are all served elsewhere`() {
            assertThatThrownBy { authorization.readableOrgs(adapter(assets = "vtfk.no")) }
                .isInstanceOf(AccessDeniedException::class.java)
                .hasMessage(DenialReason.ORG_NOT_SERVED.detail)
        }
    }

    @Nested
    inner class RequireAnswerAllowed {
        @Test
        fun `without a contract a write may be answered but a READ may not`() {
            every { contractService.lookup(any(), any()) } returns ContractLookup.Absent
            SecurityContextHolder.getContext().authentication = adapter()

            assertThatCode { authorization.requireAnswerAllowed(request(OperationType.CREATE)) }.doesNotThrowAnyException()
            assertThatThrownBy { authorization.requireAnswerAllowed(request(OperationType.READ)) }
                .isInstanceOf(AccessDeniedException::class.java)
                .hasMessage(DenialReason.EVENT_NOT_IN_CONTRACT.detail)
        }

        @Test
        fun `a contract that lists the resource decides its operations`() {
            every { contractService.lookup(any(), any()) } returns ContractLookup.Found(contractFor(MAIN_ORG, READS_ELEV))
            SecurityContextHolder.getContext().authentication = adapter()

            assertThatCode { authorization.requireAnswerAllowed(request(OperationType.READ)) }.doesNotThrowAnyException()
            assertThatThrownBy { authorization.requireAnswerAllowed(request(OperationType.CREATE)) }
                .isInstanceOf(AccessDeniedException::class.java)
                .hasMessage(DenialReason.EVENT_NOT_IN_CONTRACT.detail)
        }
    }

    private fun request(operation: OperationType): RequestFintEvent =
        RequestFintEvent().apply {
            corrId = "corr-id"
            orgId = MAIN_ORG
            domainName = "utdanning"
            packageName = "elev"
            resourceName = "elev"
            operationType = operation
        }

    private fun contractFor(
        orgId: String,
        eventCapabilities: EventCapabilities = EventCapabilities.NONE,
    ): RegisteredContract = RegisteredContract(OrgId.from(orgId), emptySet(), eventCapabilities)

    private fun adapter(assets: String = MAIN_ORG): CorePrincipal {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .claim("cn", USERNAME)
                .claim("fintAssetIDs", assets)
                .claim("scope", listOf("fint-adapter"))
                .claim("Roles", listOf("FINT_Adapter_utdanning_elev"))
                .build()
        return CorePrincipal(jwt, emptyList())
    }

    private companion object {
        const val MAIN_ORG = "fintlabs.no"
        const val SUB_ORG = "test.fintlabs.no"
        const val USERNAME = "test@adapter.fintlabs.no"
        val READS_ELEV = EventCapabilities(mapOf(resourceRefOf("utdanning", "elev", "elev") to setOf(OperationType.READ)))
    }
}
