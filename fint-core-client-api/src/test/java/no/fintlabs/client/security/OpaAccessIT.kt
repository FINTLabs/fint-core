package no.fintlabs.client.security

import no.fintlabs.client.config.ConsumerConfiguration
import no.fintlabs.client.config.JacksonConfiguration
import no.fintlabs.client.resource.dto.FintResourcesResponse
import no.fintlabs.client.resource.dto.createFintResourcesResponse
import no.fintlabs.client.security.opa.OpaClient
import no.fintlabs.client.security.opa.OpaProperties
import no.fintlabs.client.security.opa.OpaService
import no.novari.fint.core.model.FintResource
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.Person
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.felles.kompleksedatatyper.Personnavn
import no.novari.fint.core.model.utdanning.vurdering.Elevfravar
import no.novari.resource.server.authentication.CorePrincipal
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Runs the real rego policy (copied unchanged from fint-core-access-control) against a real OPA
 * container, not a mock, so a change to the input/output contract fails here instead of in
 * production. `env` is MockMvc's default host ("localhost") unless a test sets the host itself.
 * The test client is granted `localhost` and `beta`.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    classes = [OpaAccessIT.SliceApplication::class],
    properties = [
        "fint.consumer.base-url=https://api.felleskomponent.no",
        "fint.consumer.org-id=fintlabs.no",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package-name=elev",
        "fint.consumer.pod-url=http://localhost",
    ],
)
class OpaAccessIT {
    @Autowired
    private lateinit var context: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
    }

    @Test
    fun `a granted resource is allowed`() {
        mockMvc
            .perform(personRequest())
            .andExpect(status().isOk)
    }

    @Test
    fun `a resource that is not granted is denied, and the 403 says why`() {
        mockMvc
            .perform(
                get("/administrasjon/personal/personalressurs")
                    .header("x-org-id", "fintlabs.no")
                    .with(authentication(client(roles = listOf("FINT_Client_administrasjon_personal")))),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(containsString("requested resource")))
    }

    @Test
    fun `response keeps only the granted fields and relations`() {
        mockMvc
            .perform(personRequest())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.fodselsnummer").exists())
            .andExpect(jsonPath("$.navn").doesNotExist())
            .andExpect(jsonPath("$._links.self").exists())
            .andExpect(jsonPath("$._links.elev").exists())
            .andExpect(jsonPath("$._links.foreldreansvar").doesNotExist())
    }

    @Test
    fun `pruning applies when the controller returns ResponseEntity of FintResource, like getResourceById`() {
        mockMvc
            .perform(
                get("/utdanning/elev/person/fodselsnummer/01010112345")
                    .header("x-org-id", "fintlabs.no")
                    .with(authentication(client(roles = listOf("FINT_Client_utdanning_elev")))),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.fodselsnummer").exists())
            .andExpect(jsonPath("$.navn").doesNotExist())
    }

    @Test
    fun `pruning applies to every entry of a ResponseEntity of FintResourcesResponse, like getResource`() {
        mockMvc
            .perform(
                get("/utdanning/elev/person/list")
                    .header("x-org-id", "fintlabs.no")
                    .with(authentication(client(roles = listOf("FINT_Client_utdanning_elev")))),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$._embedded._entries[0].fodselsnummer").exists())
            .andExpect(jsonPath("$._embedded._entries[0].navn").doesNotExist())
            .andExpect(jsonPath("$._links.self").exists())
    }

    @Test
    fun `self link is dropped when the id field it is built from is not granted`() {
        mockMvc
            .perform(
                get("/administrasjon/personal/person")
                    .header("x-org-id", "fintlabs.no")
                    .with(authentication(client(roles = listOf("FINT_Client_administrasjon_personal")))),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.navn").exists())
            .andExpect(jsonPath("$.fodselsnummer").doesNotExist())
            .andExpect(jsonPath("$._links.self").doesNotExist())
    }

    @Test
    fun `self link is kept when the id field it is built from is granted`() {
        mockMvc
            .perform(personRequest())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$._links.self[0].href").value(containsString("/fodselsnummer/01010112345")))
    }

    @Test
    fun `a granted camelCase field is kept even though OPA lowercases field names`() {
        mockMvc
            .perform(
                get("/utdanning/vurdering/elevfravar")
                    .header("x-org-id", "fintlabs.no")
                    .with(authentication(client(roles = listOf("FINT_Client_utdanning_vurdering")))),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.systemId").exists())
    }

    @Test
    fun `endpoints overview is allowed for a granted package`() {
        mockMvc
            .perform(
                get("/utdanning/vurdering")
                    .header("x-org-id", "fintlabs.no")
                    .with(authentication(client(roles = listOf("FINT_Client_utdanning_vurdering")))),
            ).andExpect(status().isOk)
    }

    @Test
    fun `endpoints overview is denied for a package that is not granted`() {
        mockMvc
            .perform(
                get("/utdanning/kodeverk")
                    .header("x-org-id", "fintlabs.no")
                    .with(authentication(client(roles = listOf("FINT_Client_utdanning_kodeverk")))),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(containsString("requested resource")))
    }

    @Test
    fun `env sent to OPA is the first label of the request host`() {
        mockMvc
            .perform(personRequestFrom("beta.felleskomponent.no"))
            .andExpect(status().isOk)
    }

    @Test
    fun `client is denied in an environment it is not granted`() {
        mockMvc
            .perform(personRequestFrom("api.felleskomponent.no"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(containsString("requested resource")))
    }

    @Test
    fun `a resource served from a path with no saved decision is pruned to nothing`() {
        mockMvc
            .perform(get("/other").with(authentication(client())))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.fodselsnummer").doesNotExist())
            .andExpect(jsonPath("$.navn").doesNotExist())
            .andExpect(jsonPath("$._links.self").doesNotExist())
    }

    private fun personRequest() =
        get("/utdanning/elev/person")
            .header("x-org-id", "fintlabs.no")
            .with(authentication(client(roles = listOf("FINT_Client_utdanning_elev"))))

    private fun personRequestFrom(host: String) =
        personRequest().with { request -> request.apply { serverName = host } }

    private fun client(
        cn: String = "test@client.fintlabs.no",
        scope: String = "fint-client",
        assets: String = "fintlabs.no",
        roles: List<String> = emptyList(),
    ): CorePrincipal {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .claim("cn", cn)
                .claim("fintAssetIDs", assets)
                .claim("scope", listOf(scope))
                .claim("Roles", roles)
                .build()
        return CorePrincipal(jwt, emptyList())
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(OpaProperties::class, ConsumerConfiguration::class)
    @Import(
        SecurityConfiguration::class,
        SecurityProblemDetailHandler::class,
        OpaFieldAdvice::class,
        OpaClient::class,
        OpaService::class,
        JacksonConfiguration::class,
        Endpoints::class,
    )
    class SliceApplication

    @RestController
    class Endpoints {
        @GetMapping("/{domainName}/{packageName}/person")
        fun person(
            @PathVariable domainName: String,
            @PathVariable packageName: String,
            @RequestHeader("x-org-id") orgId: String,
        ): Person = newPerson()

        @GetMapping("/{domainName}/{packageName}/person/fodselsnummer/{idValue}")
        fun personById(
            @PathVariable domainName: String,
            @PathVariable packageName: String,
            @PathVariable idValue: String,
            @RequestHeader("x-org-id") orgId: String,
        ): ResponseEntity<FintResource> = ResponseEntity.ok(newPerson())

        @GetMapping("/{domainName}/{packageName}/person/list")
        fun personList(
            @PathVariable domainName: String,
            @PathVariable packageName: String,
            @RequestHeader("x-org-id") orgId: String,
        ): ResponseEntity<FintResourcesResponse> =
            ResponseEntity.ok(
                createFintResourcesResponse(
                    "https://api.felleskomponent.no",
                    "utdanning/elev/person",
                    listOf(newPerson()),
                    0,
                    0,
                    1,
                ),
            )

        @GetMapping("/{domainName}/{packageName}/elevfravar")
        fun elevfravar(
            @PathVariable domainName: String,
            @PathVariable packageName: String,
            @RequestHeader("x-org-id") orgId: String,
        ): Elevfravar = Elevfravar(systemId = Identifikator(identifikatorverdi = "42"))

        @GetMapping("/{domainName}/{packageName}")
        fun endpoints(
            @PathVariable domainName: String,
            @PathVariable packageName: String,
            @RequestHeader("x-org-id") orgId: String,
        ): String = "$domainName/$packageName"

        @GetMapping("/other")
        fun outsideResourcePaths(): Person = newPerson()

        private fun newPerson(): Person =
            Person(
                fodselsnummer = Identifikator(identifikatorverdi = "01010112345"),
                navn = Personnavn(fornavn = "Kari", etternavn = "Nordmann"),
            ).apply {
                addLink("elev", Link(idField = "systemid", idValue = "1"))
                addLink("foreldreansvar", Link(idField = "fodselsnummer", idValue = "02020254321"))
            }

        @GetMapping("/{domainName}/{packageName}/{resourceName}")
        fun other(
            @PathVariable domainName: String,
            @PathVariable packageName: String,
            @PathVariable resourceName: String,
            @RequestHeader("x-org-id") orgId: String,
        ): String = "$domainName/$packageName/$resourceName"
    }

    class ClientGrants(
        val allowedEnvironments: List<String>,
        val components: List<Component>,
    )

    class Component(
        val domainName: String,
        val packageName: String,
        vararg resources: Resource,
    ) {
        val resources: List<Resource> = resources.toList()
    }

    class Resource(
        val resourceName: String,
        val fields: List<String> = emptyList(),
        val relations: List<String> = emptyList(),
    )

    companion object {
        private val opa =
            GenericContainer(DockerImageName.parse("openpolicyagent/opa:1.20.2"))
                .withExposedPorts(8181)
                .withCommand("run", "--server", "--addr=0.0.0.0:8181", "--set=decision_logs.console=true")

        @JvmStatic
        @DynamicPropertySource
        fun opaProperties(registry: DynamicPropertyRegistry) {
            opa.start()
            val opaUrl = "http://${opa.host}:${opa.getMappedPort(8181)}"
            val restClient = RestClient.builder().baseUrl(opaUrl).build()
            restClient
                .put()
                .uri("/v1/policies/core")
                .contentType(MediaType.TEXT_PLAIN)
                .body(COMPONENT_ACCESS_POLICY)
                .retrieve()
                .toBodilessEntity()
            restClient
                .put()
                .uri("/v1/data/clients")
                .contentType(MediaType.APPLICATION_JSON)
                .body(grants)
                .retrieve()
                .toBodilessEntity()
            registry.add("fint.security.opa.enabled") { "true" }
            registry.add("fint.security.opa.url") { opaUrl }
        }

        // Copied unchanged from FINTLabs/fint-core-access-control's
        // src/main/resources/opa/policy/auth.txt, package `core`.
        private val COMPONENT_ACCESS_POLICY =
            """
            package core

            import rego.v1

            default allow := false
            default fields := []
            default relations := []

            allow if {
                not input.resourceName
                client := get_client()
                _ := get_component(client)
            }

            allow if {
                client := get_client()
                component := get_component(client)
                _ := get_resource(component)
            }

            get_client() := client if {
                some uname
                client := data.clients[uname]
                lower(uname) == lower(input.username)
                not client.adapter
                allowed := [ lower(env) | env := client.allowedEnvironments[_] ]
                lower(input.env) in allowed
            }

            get_component(client) := component if {
                some i
                component := client.components[i]
                lower(component.domainName)  == lower(input.domainName)
                lower(component.packageName) == lower(input.packageName)
            }

            get_resource(component) := resource if {
                some j
                resource := component.resources[j]
                lower(resource.resourceName) == lower(input.resourceName)
            }

            fields := [ lower(field) | field := get_resource(get_component(get_client())).fields[_] ]

            relations := [ lower(relation) | relation := get_resource(get_component(get_client())).relations[_] ]
            """.trimIndent()

        /**
         * What the test client is granted, which the rego reads as `data.clients[username]`. The
         * elev person has a field and a relation. The personal person is the same resource without
         * its id field, so its self link must go. The vurdering elevfravar has a camelCase field.
         */
        private val grants =
            mapOf(
                "test@client.fintlabs.no" to
                    ClientGrants(
                        allowedEnvironments = listOf("localhost", "beta"),
                        components =
                            listOf(
                                Component(
                                    "utdanning",
                                    "elev",
                                    Resource("person", fields = listOf("fodselsnummer"), relations = listOf("elev")),
                                ),
                                Component(
                                    "administrasjon",
                                    "personal",
                                    Resource("person", fields = listOf("navn")),
                                ),
                                Component(
                                    "utdanning",
                                    "vurdering",
                                    Resource("elevfravar", fields = listOf("systemId")),
                                ),
                            ),
                    ),
            )
    }
}
