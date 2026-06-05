package no.novari.fint.core.consumer.resource

import io.mockk.every
import io.mockk.mockk
import no.novari.fint.core.consumer.exception.resource.IdentificatorNotFoundException
import no.novari.fint.core.consumer.exception.resource.ResourceNotFoundException
import no.novari.fint.core.consumer.exception.resource.ResourceNotWriteableException
import no.novari.fint.core.consumer.resource.aspect.IdentifierAspect
import no.novari.fint.core.consumer.resource.aspect.ResourceAspect
import no.novari.fint.core.consumer.resource.aspect.WriteableAspect
import no.novari.fint.core.shared.resource.context.ResourceContext
import no.novari.metamodel.MetamodelService
import no.novari.metamodel.model.Resource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class ResourceAspectTest {
    private val metamodelService = mockk<MetamodelService>()
    private val resourceContext = mockk<ResourceContext>()
    private val resourceAspect = ResourceAspect(metamodelService)
    private val identifierAspect = IdentifierAspect(resourceContext)
    private val writeableAspect = WriteableAspect(resourceContext)

    private val domain = "utdanning"
    private val pkg = "elev"
    private val resource = "elev"
    private val key = "utdanning_elev_elev"

    @Test
    fun `checkResource passes for a valid triple`() {
        every { metamodelService.getResource(domain, pkg, resource) } returns mockk<Resource>()
        assertDoesNotThrow { resourceAspect.checkResource(domain, pkg, resource) }
    }

    @Test
    fun `checkResource throws for an invalid triple`() {
        every { metamodelService.getResource(any(), any(), any()) } returns null
        assertThrows<ResourceNotFoundException> { resourceAspect.checkResource(domain, pkg, "nope") }
    }

    @Test
    fun `checkIdField passes when the id field exists`() {
        every { resourceContext.resourceHasIdField(key, "systemid") } returns true
        assertDoesNotThrow { identifierAspect.checkIdField(domain, pkg, resource, "systemid") }
    }

    @Test
    fun `checkIdField throws when the id field does not exist`() {
        every { resourceContext.resourceHasIdField(key, "fodselsnummer") } returns false
        assertThrows<IdentificatorNotFoundException> {
            identifierAspect.checkIdField(domain, pkg, resource, "fodselsnummer")
        }
    }

    @Test
    fun `checkWriteable passes for a writeable resource`() {
        every { resourceContext.resourceIsWriteable(key) } returns true
        assertDoesNotThrow { writeableAspect.checkWriteable(domain, pkg, resource) }
    }

    @Test
    fun `checkWriteable throws for a non-writeable resource`() {
        every { resourceContext.resourceIsWriteable(key) } returns false
        assertThrows<ResourceNotWriteableException> { writeableAspect.checkWriteable(domain, pkg, resource) }
    }
}
