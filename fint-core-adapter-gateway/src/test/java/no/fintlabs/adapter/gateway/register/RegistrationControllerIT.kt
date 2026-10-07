package no.fintlabs.adapter.gateway.register

import no.fintlabs.adapter.gateway.GatewayIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class RegistrationControllerIT
    @Autowired
    constructor(
        private val contractJpaRepository: ContractJpaRepository,
    ) : GatewayIntegrationTestBase() {
        @Test
        fun `Should successfully register adapter`() {
            mockMvc
                .perform(
                    post("/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(adapterContract()))
                        .with(authentication(mockPrincipal)),
                ).andExpect(status().isOk)
        }

        @Test
        fun `verify contracts get saved to database when registering adapter`() {
            registerAdapter()

            val stored = contractJpaRepository.findByUserNameAndOrgId(username, orgId)

            assert(stored?.adapterId == "https://test.com/test.fintlabs.no/utdanning/elev")
        }
    }
