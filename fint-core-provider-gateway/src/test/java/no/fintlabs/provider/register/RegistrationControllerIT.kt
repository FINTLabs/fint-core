package no.fintlabs.provider.register

import no.fintlabs.provider.GatewayIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class RegistrationControllerIT
    @Autowired
    constructor(
        contractJpaRepository: ContractJpaRepository,
    ) : GatewayIntegrationTestBase() {
        private val contractService: ContractService = ContractService(contractJpaRepository)

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

            val adapterIds = contractService.getAdapterIds()

            assert(adapterIds.contains("https://test.com/test.org.no/utdanning/elev"))
        }
    }
