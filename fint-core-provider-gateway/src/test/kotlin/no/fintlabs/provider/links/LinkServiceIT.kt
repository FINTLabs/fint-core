package no.fintlabs.provider.links

import no.novari.metamodel.MetamodelService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class LinkServiceIT {
    @Autowired
    lateinit var linkService: LinkService

    @Autowired
    lateinit var metamodelService: MetamodelService
}
