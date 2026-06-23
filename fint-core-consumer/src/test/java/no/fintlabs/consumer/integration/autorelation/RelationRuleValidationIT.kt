package no.fintlabs.consumer.integration.autorelation

import no.fintlabs.Application
import no.fintlabs.autorelation.cache.RelationRuleBuilder
import no.fintlabs.autorelation.model.EntityDescriptor
import no.fintlabs.autorelation.model.RelationSyncRule
import no.novari.metamodel.MetamodelService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertTrue

/**
 * Validates that the RelationRuleBuilder generates only well-formed rules.
 *
 * Each rule must be either:
 *   - OneToMany  (trigger has ONE link → target gets MANY back-links)
 *   - ManyToMany source-side (trigger has MANY links → targets get MANY back-links, source drives the update)
 *
 * Skipped types (OneToOne, ManyToOne, non-source ManyToMany) must produce no rules.
 * Common resources (felles) must be included after the isManagedRelation fix.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [Application::class])
@EmbeddedKafka(partitions = 1)
@TestPropertySource(
    properties = [
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.group-id=relation-rule-validation-it",

        "novari.kafka.default-replicas=1",
        "fint.relation.base-url=https://test.felleskomponent.no",
        "fint.org-id=foo.org",
        "fint.consumer.org-id=foo.org",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package=elev",
        "fint.consumer.autorelation.enabled=true",
        "fint.security.enabled=false",
    ],
)
@DirtiesContext
class RelationRuleValidationIT {
    @Autowired
    lateinit var ruleBuilder: RelationRuleBuilder

    @Autowired
    lateinit var metamodelService: MetamodelService

    @Test
    fun `all generated rules have valid multiplicity`() {
        val allRules = ruleBuilder.buildEntityDescriptorToRules()

        assertTrue(allRules.isNotEmpty()) { "Expected at least one rule to be generated" }

        val invalid =
            allRules.entries.flatMap { (trigger, rules) ->
                rules.filterNot { it.isValid() }.map { it.describe(trigger) }
            }

        assertTrue(invalid.isEmpty()) {
            "Found rules with invalid multiplicity — only OneToMany and source-side ManyToMany are supported:\n" +
                invalid.joinToString("\n")
        }
    }

    @Test
    fun `no non-source ManyToMany rules are generated`() {
        val allRules = ruleBuilder.buildEntityDescriptorToRules()

        val nonSource =
            allRules.entries.flatMap { (trigger, rules) ->
                rules.filter { it.isManyToMany() && !it.isSource }.map { it.describe(trigger) }
            }

        assertTrue(nonSource.isEmpty()) {
            "Found non-source ManyToMany rules — only the source side should drive updates:\n" +
                nonSource.joinToString("\n")
        }
    }

    @Test
    fun `common resource relations are included in generated rules`() {
        val allRules = ruleBuilder.buildEntityDescriptorToRules()
        val allTargetRelations = allRules.values.flatten()

        // Common resources (felles) should now produce rules after the isManagedRelation fix.
        // At minimum, at least one rule must target a resource whose trigger descriptor uses
        // the component package — which is how common resource targets are registered.
        val commonResourceRules =
            allRules.entries
                .filter { (_, rules) -> rules.any { it.targetType.packageName == "elev" && it.isValid() } }

        assertTrue(commonResourceRules.isNotEmpty()) {
            "Expected at least one rule involving a common-resource target (packageName=elev). " +
                "Found targets: ${
                    allTargetRelations.map {
                        "${it.targetType.packageName}.${it.targetType.resourceName}"
                    }.distinct().sorted()
                }"
        }
    }

    @Test
    fun `inverseRelation names exist on their target resources`() {
        val allRules = ruleBuilder.buildEntityDescriptorToRules()

        val broken =
            allRules.entries.flatMap { (trigger, rules) ->
                rules
                    .filter { rule ->
                        val relations =
                            metamodelService
                                .getResource(
                                    rule.targetType.domainName,
                                    rule.targetType.packageName,
                                    rule.targetType.resourceName,
                                )?.relations
                        relations == null || relations.none { it.name == rule.inverseRelation }
                    }.map { it.describe(trigger) }
            }

        assertTrue(broken.isEmpty()) {
            "Found rules where inverseRelation does not exist on the target resource — " +
                "this would make the back-link update target a non-existent relation:\n" +
                broken.joinToString("\n")
        }
    }

    @Test
    fun `inverseRelation points back to the trigger relation (symmetry)`() {
        val allRules = ruleBuilder.buildEntityDescriptorToRules()

        val asymmetric =
            allRules.entries.flatMap { (trigger, rules) ->
                rules
                    .filter { rule ->
                        val inverseRelationOnTarget =
                            metamodelService
                                .getResource(
                                    rule.targetType.domainName,
                                    rule.targetType.packageName,
                                    rule.targetType.resourceName,
                                )?.relations
                                ?.firstOrNull { it.name == rule.inverseRelation }
                        // The inverse relation's own inverseName must point back to the forward relation
                        inverseRelationOnTarget?.inverseName != rule.targetRelation
                    }.map { it.describe(trigger) }
            }

        assertTrue(asymmetric.isEmpty()) {
            "Found rules where inverseRelation.inverseName does not equal targetRelation — " +
                "the relation pair is not symmetric in the metamodel:\n" +
                asymmetric.joinToString("\n")
        }
    }

    @Test
    fun `no rule has blank targetRelation or inverseRelation`() {
        val allRules = ruleBuilder.buildEntityDescriptorToRules()

        val blank =
            allRules.entries.flatMap { (trigger, rules) ->
                rules
                    .filter { it.targetRelation.isBlank() || it.inverseRelation.isBlank() }
                    .map { it.describe(trigger) }
            }

        assertTrue(blank.isEmpty()) {
            "Found rules with blank targetRelation or inverseRelation:\n" + blank.joinToString("\n")
        }
    }

    @Test
    fun `no duplicate rules per trigger`() {
        val allRules = ruleBuilder.buildEntityDescriptorToRules()

        val duplicates =
            allRules.entries.flatMap { (trigger, rules) ->
                rules
                    .groupBy { it.targetRelation }
                    .filter { (_, group) -> group.size > 1 }
                    .map { (targetRelation, group) ->
                        "[${trigger.domainName}-${trigger.packageName}-${trigger.resourceName}] " +
                            "targetRelation='$targetRelation' appears ${group.size} times"
                    }
            }

        assertTrue(duplicates.isEmpty()) {
            "Found duplicate rules for the same trigger+targetRelation — would cause double updates:\n" +
                duplicates.joinToString("\n")
        }
    }

    @Test
    fun `all targetType resources exist in the metamodel`() {
        val allRules = ruleBuilder.buildEntityDescriptorToRules()

        val missing =
            allRules.entries.flatMap { (trigger, rules) ->
                rules
                    .filter { rule ->
                        metamodelService.getResource(
                            rule.targetType.domainName,
                            rule.targetType.packageName,
                            rule.targetType.resourceName,
                        ) == null
                    }.map { it.describe(trigger) }
            }

        assertTrue(missing.isEmpty()) {
            "Found rules whose targetType does not resolve to a known metamodel resource:\n" +
                missing.joinToString("\n")
        }
    }

    @Test
    fun `targetRelation exists on its trigger resource`() {
        val allRules = ruleBuilder.buildEntityDescriptorToRules()

        val missing =
            allRules.entries.flatMap { (trigger, rules) ->
                val triggerRelations =
                    metamodelService
                        .getResource(trigger.domainName, trigger.packageName, trigger.resourceName)
                        ?.relations
                rules
                    .filter { rule ->
                        triggerRelations == null || triggerRelations.none { it.name == rule.targetRelation }
                    }.map { it.describe(trigger) }
            }

        assertTrue(missing.isEmpty()) {
            "Found rules where targetRelation does not exist on the trigger resource:\n" +
                missing.joinToString("\n")
        }
    }

    private fun RelationSyncRule.isValid() = isOneToMany() || (isManyToMany() && isSource)

    private fun RelationSyncRule.describe(trigger: EntityDescriptor) =
        "[${trigger.domainName}-${trigger.packageName}-${trigger.resourceName}] " +
            "$targetRelation → $inverseRelation " +
            "(target=$targetMultiplicity, inverse=$inverseMultiplicity, isSource=$isSource)"
}
