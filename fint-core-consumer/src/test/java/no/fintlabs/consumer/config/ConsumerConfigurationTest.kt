package no.fintlabs.consumer.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsumerConfigurationTest {
    private val consumerConfiguration =
        ConsumerConfiguration(
            baseUrl = "https://testorg.no",
            orgIdValue = "foo.org",
            domain = "domain",
            packageName = "package",
            podUrl = "http://consumer.test",
        )

    @Test
    fun getComponentUrl() {
        assertEquals("https://testorg.no/domain/package", consumerConfiguration.componentUrl)
        assertEquals(OrgId.from("foo.org"), consumerConfiguration.orgId)
    }

    @ParameterizedTest
    @CsvSource(
        "domain, package, foo.org",
        "domAin, packagE, fOO.org",
        "dOmain, PAckage, foo.OrG",
    )
    fun `matchesConfiguration is true when domain, package and orgId is correct, independent of case`(
        domain: String,
        pkg: String,
        orgId: String,
    ) = assertTrue(consumerConfiguration.matchesConfiguration(domain, pkg, orgId))

    @ParameterizedTest
    @CsvSource(
        "domain, package, foo_org",
        "domain, package, foo_ORG",
        "domain, package, foo-ORG",
    )
    fun `matchesConfiguration is true when orgId contains dash or underscore instead of dot`(
        domain: String,
        pkg: String,
        orgId: String,
    ) = assertTrue(consumerConfiguration.matchesConfiguration(domain, pkg, orgId))

    @ParameterizedTest
    @CsvSource(
        "another-domain, package, org",
        "domain, another-package, org",
        "domain, package, another-org",
        "domain, package, foo..org",
        "domain, package, foo--org",
    )
    fun `matchesConfiguration is false when domain, package and orgId does not match`(
        domain: String,
        pkg: String,
        orgId: String,
    ) = assertFalse(consumerConfiguration.matchesConfiguration(domain, pkg, orgId))
}
