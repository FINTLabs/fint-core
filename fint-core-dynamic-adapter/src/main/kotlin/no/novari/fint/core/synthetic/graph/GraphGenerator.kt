package no.novari.fint.core.synthetic.graph

import no.novari.fint.core.shared.autorelation.cache.RelationRuleRegistry
import no.novari.fint.core.shared.autorelation.model.EntityDescriptor
import no.novari.fint.core.shared.autorelation.model.createEntityDescriptor
import no.novari.fint.core.shared.autorelation.model.toEntityDescriptor
import no.novari.fint.core.synthetic.config.RelationSpec
import no.novari.fint.core.synthetic.config.ResourceSpec
import no.novari.fint.core.synthetic.config.SyntheticProperties
import no.novari.fint.model.FintMultiplicity
import no.novari.fint.model.FintRelation
import no.novari.metamodel.MetamodelService
import no.novari.metamodel.model.Resource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Builds the synthetic entity graph in memory: first the entities, then the links between them.
 *
 * Linking, in short: for every resource in the run, every relation in the FINT metamodel is wired
 * automatically when the target resource is also in the run. Required relations link exactly 1
 * target, optional ones 0..1, and targets are picked seeded-randomly from the generated pool — so
 * a link can never point at something that doesn't exist. YAML `relations` config overrides any of
 * this per relation.
 *
 * Three things are never auto-wired (see [effectiveRelations] and [ownsPairWiring]):
 * 1. Relations whose target is not part of the run (warn-logged when required).
 * 2. Back-link sides owned by autorelation — they are derived from the forward side instead.
 * 3. The losing side of a bidirectional pair without an autorelation rule (1:1 pairs like
 *    elev/person): only one side may link independently, or the two directions would disagree.
 */
@Service
class GraphGenerator(
    private val metamodelService: MetamodelService,
    private val ruleRegistry: RelationRuleRegistry,
) {
    private val logger = LoggerFactory.getLogger(GraphGenerator::class.java)

    fun generate(config: SyntheticProperties): Generation {
        require(config.resources.isNotEmpty()) { "synthetic.resources must not be empty" }
        val rng = Random(config.seed)
        val notes = mutableListOf<String>()

        val specs: Map<EntityDescriptor, ResourceSpec> =
            config.resources.entries
                .associate { (key, spec) -> key.toEntityDescriptor() to spec }
                .onEach { (descriptor, _) -> metamodelResource(descriptor) }
                .filter { (descriptor, spec) ->
                    val count = spec.resolveCount(config.scale)
                    if (count <= 0 && (spec.count ?: 0) > 0) {
                        note(notes, "${descriptor.key()}: count ${spec.count} x scale ${config.scale} rounded to zero; resource dropped")
                    }
                    count > 0
                }

        val entities: Map<EntityDescriptor, List<SyntheticEntity>> =
            specs.entries.associate { (descriptor, spec) ->
                val count = spec.resolveCount(config.scale)
                descriptor to List(count) { index -> SyntheticEntity("${descriptor.resourceName}-${index + 1}") }
            }

        specs.forEach { (descriptor, spec) ->
            effectiveRelations(descriptor, spec, specs, entities.keys, notes).forEach { (relationName, relationSpec) ->
                wireRelation(descriptor, relationName, relationSpec, entities, rng, notes)
            }
        }

        return Generation(entities, notes)
    }

    private fun note(
        notes: MutableList<String>,
        message: String,
    ) {
        logger.warn(message)
        notes += message
    }

    /**
     * Decides which relations a resource actually generates: everything from the metamodel that
     * survives the three exclusion rules, with explicit YAML config layered on top (config wins).
     */
    private fun effectiveRelations(
        descriptor: EntityDescriptor,
        spec: ResourceSpec,
        specs: Map<EntityDescriptor, ResourceSpec>,
        present: Set<EntityDescriptor>,
        notes: MutableList<String>,
    ): Map<String, RelationSpec> {
        val explicit = spec.relations.mapKeys { (name, _) -> name.lowercase() }
        val managedInverses = ruleRegistry.getInverseRelations(descriptor).map { it.lowercase() }.toSet()
        val auto =
            metamodelResource(descriptor)
                .relations
                .asSequence()
                .filter { it.name.lowercase() !in managedInverses }
                .filter { it.name.lowercase() !in explicit }
                .mapNotNull { relation ->
                    val target = relation.targetDescriptor(descriptor)
                    when {
                        target !in present -> {
                            if (relation.isRequired()) {
                                note(
                                    notes,
                                    "${descriptor.key()}: required relation '${relation.name}' -> ${target.key()} " +
                                        "not wired; target is not part of this run",
                                )
                            }
                            null
                        }

                        !ownsPairWiring(descriptor, relation, target, specs) -> {
                            null
                        }

                        else -> {
                            relation.name.lowercase() to RelationSpec()
                        }
                    }
                }.toMap()
        return explicit + auto
    }

    /**
     * For bidirectional pairs with no autorelation rule (1:1 pairs), exactly one side may wire
     * links independently — the other side only mirrors them. Picks the owner deterministically:
     * required beats optional, to-one beats to-many, then the source flag, then name order.
     * Returns `true` when this side is the owner.
     */
    private fun ownsPairWiring(
        descriptor: EntityDescriptor,
        relation: FintRelation,
        target: EntityDescriptor,
        specs: Map<EntityDescriptor, ResourceSpec>,
    ): Boolean {
        val inverseName = relation.inverseName ?: return true
        if (hasRule(descriptor, relation.name)) return true
        if (specs[target]?.relations?.keys?.any { it.equals(inverseName, ignoreCase = true) } == true) return false
        val inverse =
            metamodelResource(target).relations.firstOrNull { it.name.equals(inverseName, ignoreCase = true) }
                ?: return true
        if (hasRule(target, inverse.name)) return false

        val mine = "$descriptor.${relation.name}"
        val theirs = "$target.${inverse.name}"
        if (mine == theirs) return true
        if (relation.pairScore() != inverse.pairScore()) return relation.pairScore() > inverse.pairScore()
        if (relation.isSource != inverse.isSource) return relation.isSource == true
        return mine < theirs
    }

    private fun FintRelation.pairScore(): Int =
        (if (isRequired()) 2 else 0) +
            (if (multiplicity == FintMultiplicity.ONE_TO_ONE || multiplicity == FintMultiplicity.NONE_TO_ONE) 1 else 0)

    private fun FintRelation.isRequired(): Boolean =
        multiplicity == FintMultiplicity.ONE_TO_ONE || multiplicity == FintMultiplicity.ONE_TO_MANY

    private fun hasRule(
        descriptor: EntityDescriptor,
        relationName: String,
    ): Boolean = ruleRegistry.getRules(descriptor).any { it.targetRelation.equals(relationName, ignoreCase = true) }

    /**
     * Creates the actual links for one relation: each source entity picks its targets from the
     * generated pool (cardinality from config or the model's multiplicity). The reverse direction
     * is recorded at the same time — as an autorelation back-link when a rule covers the pair
     * (emitted only when materializing), otherwise as a plain inverse link on the target.
     *
     * When the inverse side of a rule-less pair is to-one (1:1 pairs like elev/person), targets
     * are drawn without replacement: each target may be linked by at most one source, so the
     * mirrored link never exceeds the inverse multiplicity.
     */
    private fun wireRelation(
        descriptor: EntityDescriptor,
        relationName: String,
        relationSpec: RelationSpec,
        entities: Map<EntityDescriptor, List<SyntheticEntity>>,
        rng: Random,
        notes: MutableList<String>,
    ) {
        val resource = metamodelResource(descriptor)
        val relation =
            resource.relations.firstOrNull { it.name.equals(relationName, ignoreCase = true) }
                ?: error(
                    "$descriptor has no relation '$relationName'; available: ${resource.relations.map { it.name }}",
                )
        val target = relationSpec.target?.toEntityDescriptor() ?: relation.targetDescriptor(descriptor)
        val targetPool =
            entities[target]
                ?: error(
                    "relation $descriptor.$relationName targets $target, which is not configured under " +
                        "synthetic.resources (or its count resolved to zero)",
                )
        val cardinality =
            relationSpec.cardinality
                ?.let { parseCardinality(it, descriptor, relationName) }
                ?: defaultCardinality(relation.multiplicity)
        val rule =
            ruleRegistry
                .getRules(descriptor)
                .firstOrNull { it.targetRelation.equals(relation.name, ignoreCase = true) }

        val picker = picker(descriptor, relation, target, targetPool, rule != null, rng)
        val sources = entities.getValue(descriptor)
        sources.forEach { entity ->
            picker(entity, cardinality.random(rng))
                .forEach { picked ->
                    entity.links.getOrPut(relation.name) { linkedSetOf() } += picked.id
                    when {
                        rule != null -> {
                            picked.autoRelationLinks.getOrPut(rule.inverseRelation) { linkedSetOf() } += entity.id
                        }

                        relation.inverseName != null -> {
                            picked.links.getOrPut(relation.inverseName) { linkedSetOf() } += entity.id
                        }
                    }
                }
        }

        if (cardinality.first > 0) {
            val unlinked = sources.count { it.links[relation.name].isNullOrEmpty() }
            if (unlinked > 0) {
                note(
                    notes,
                    "${descriptor.key()}: $unlinked of ${sources.size} entities got no '${relation.name}' link; " +
                        "the ${target.key()} pool is too small",
                )
            }
        }
    }

    private fun picker(
        descriptor: EntityDescriptor,
        relation: FintRelation,
        target: EntityDescriptor,
        targetPool: List<SyntheticEntity>,
        hasRule: Boolean,
        rng: Random,
    ): (SyntheticEntity, Int) -> List<SyntheticEntity> {
        val inverse =
            relation.inverseName?.let { name ->
                metamodelResource(target).relations.firstOrNull { it.name.equals(name, ignoreCase = true) }
            }
        val inverseIsToOne =
            (inverse?.multiplicity ?: relation.multiplicity).let {
                it == FintMultiplicity.ONE_TO_ONE || it == FintMultiplicity.NONE_TO_ONE
            }
        return if (!hasRule && relation.inverseName != null && inverseIsToOne) {
            exclusivePicker(targetPool, rng)
        } else {
            { self, amount -> pickTargets(targetPool, amount, rng, excludeSelf = target == descriptor, self = self) }
        }
    }

    private fun exclusivePicker(
        pool: List<SyntheticEntity>,
        rng: Random,
    ): (SyntheticEntity, Int) -> List<SyntheticEntity> {
        val remaining = ArrayDeque(pool.shuffled(rng))
        return { self, amount ->
            val picked = mutableListOf<SyntheticEntity>()
            var skippedSelf: SyntheticEntity? = null
            while (picked.size < amount && remaining.isNotEmpty()) {
                val candidate = remaining.removeFirst()
                if (candidate === self) skippedSelf = candidate else picked += candidate
            }
            skippedSelf?.let(remaining::addFirst)
            picked
        }
    }

    private fun metamodelResource(descriptor: EntityDescriptor): Resource =
        metamodelService.getResource(descriptor.domainName, descriptor.packageName, descriptor.resourceName)
            ?: error("Unknown resource $descriptor; expected format domain-package-resource, e.g. utdanning-elev-elev")

    private fun ResourceSpec.resolveCount(scale: Double): Int {
        val count = requireNotNull(count) { "count is required for every resource under synthetic.resources" }
        require(count >= 0) { "count must not be negative" }
        return if (fixed) count else (count * scale).roundToInt()
    }

    private fun defaultCardinality(multiplicity: FintMultiplicity): IntRange =
        when (multiplicity) {
            FintMultiplicity.ONE_TO_ONE, FintMultiplicity.ONE_TO_MANY -> 1..1
            FintMultiplicity.NONE_TO_ONE, FintMultiplicity.NONE_TO_MANY -> 0..1
        }

    private fun FintRelation.targetDescriptor(source: EntityDescriptor): EntityDescriptor {
        val parts = packageName.split(".")
        return if (parts.size == 6) {
            createEntityDescriptor(source.domainName, source.packageName, parts.last())
        } else {
            parts.takeLast(3).let { (domain, pkg, resource) -> createEntityDescriptor(domain, pkg, resource) }
        }
    }

    private fun parseCardinality(
        value: String,
        descriptor: EntityDescriptor,
        relationName: String,
    ): IntRange {
        val parts = value.split("..")
        val range =
            when (parts.size) {
                1 -> {
                    parts[0].trim().toIntOrNull()?.let { it..it }
                }

                2 -> {
                    val min = parts[0].trim().toIntOrNull()
                    val max = parts[1].trim().toIntOrNull()
                    if (min != null && max != null) min..max else null
                }

                else -> {
                    null
                }
            }
        require(range != null && range.first >= 0 && range.last >= range.first) {
            "$descriptor.$relationName: invalid cardinality '$value'; use a number or a range like '0..3'"
        }
        return range
    }

    private fun pickTargets(
        pool: List<SyntheticEntity>,
        amount: Int,
        rng: Random,
        excludeSelf: Boolean,
        self: SyntheticEntity,
    ): List<SyntheticEntity> {
        val candidates = if (excludeSelf) pool.filterNot { it === self } else pool
        if (amount >= candidates.size) return candidates
        val indices = linkedSetOf<Int>()
        while (indices.size < amount) {
            indices += rng.nextInt(candidates.size)
        }
        return indices.map(candidates::get)
    }
}
