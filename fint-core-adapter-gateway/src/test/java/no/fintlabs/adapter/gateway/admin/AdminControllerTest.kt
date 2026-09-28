package no.fintlabs.adapter.gateway.admin

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.adapter.gateway.relation.RelationEdgeDrift
import no.fintlabs.adapter.gateway.relation.RelationEdgeRebuild
import no.fintlabs.adapter.gateway.relation.RelationEdgeRebuildRunningException
import no.fintlabs.adapter.gateway.relation.RelationEdgeRebuilder
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.resource.server.authentication.CorePrincipal
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals

class AdminControllerTest {
    private val rebuilder = mockk<RelationEdgeRebuilder>()
    private val controller = AdminController(rebuilder)
    private val novariClient =
        CorePrincipal(
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .claim("cn", "admin@client.novari.no")
                .claim("fintAssetIDs", "novari.no")
                .claim("scope", listOf("fint-client"))
                .build(),
            emptyList(),
        )

    init {
        every { rebuilder.rebuild(any()) } returns RelationEdgeRebuild(resourcesRead = 3, edgesWritten = 2, edgesRemoved = 1)
        every { rebuilder.drift(any()) } returns RelationEdgeDrift.NONE
    }

    @Test
    fun `the org id and resource path become the coordinate that is rebuilt`() {
        val rebuild = controller.rebuild("ude-oslo-kommune-no", "utdanning/elev/person", novariClient)

        verify { rebuilder.rebuild(ResourceCoordinate("ude.oslo.kommune.no", "utdanning", "elev", "person")) }
        assertEquals(RelationEdgeRebuild(resourcesRead = 3, edgesWritten = 2, edgesRemoved = 1), rebuild)
    }

    @Test
    fun `a drift check runs on the same coordinate a rebuild would`() {
        controller.drift("ude-oslo-kommune-no", "utdanning/elev/person", novariClient)

        verify { rebuilder.drift(ResourceCoordinate("ude.oslo.kommune.no", "utdanning", "elev", "person")) }
    }

    @Test
    fun `an iso path is rebuilt under the identity the model gives it`() {
        controller.rebuild("fintlabs.no", "felles/kodeverk/iso/landkode", novariClient)

        verify { rebuilder.rebuild(ResourceCoordinate("fintlabs.no", "felles", "kodeverk", "landkode")) }
    }

    @Test
    fun `a resource the model does not serve is a bad request`() {
        val exception =
            assertThrows<ResponseStatusException> { controller.rebuild("fintlabs.no", "utdanning/elev/nothing", novariClient) }

        assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    }

    @Test
    fun `a blank org id is a bad request`() {
        val exception = assertThrows<ResponseStatusException> { controller.rebuild(" ", "utdanning/elev/person", novariClient) }

        assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    }

    @Test
    fun `a rebuild that is already running answers conflict`() {
        val coordinate = ResourceCoordinate("fintlabs.no", "utdanning", "elev", "person")

        val problem = controller.alreadyRunning(RelationEdgeRebuildRunningException(coordinate))

        assertEquals(HttpStatus.CONFLICT.value(), problem.status)
    }
}
