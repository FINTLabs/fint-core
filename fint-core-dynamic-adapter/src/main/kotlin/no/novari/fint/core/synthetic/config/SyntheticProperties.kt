package no.novari.fint.core.synthetic.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for a generator run, bound from `synthetic.*`.
 *
 * The built-in dataset ([Datasets.utdanning]) is always the starting point; anything you put
 * under [resources] overrides it per resource key.
 *
 * @property seed Random seed. Same seed + same config = exactly the same data every run.
 * @property scale Volume knob. `1.0` = production size, `0.02` = 2% of production.
 *   Multiplies every [ResourceSpec.count] except those marked [ResourceSpec.fixed].
 * @property pageSize Max resources per sync-page file.
 * @property outputDir Where the JSON files are written.
 * @property orgId Org id stamped into every page's metadata.
 * @property materializeBackLinks `false` = leave back-links out so autorelation has to create
 *   them (the normal test case). `true` = write both directions of every relation into the files.
 * @property populateFields `true` = fill every field of the resource (names, dates, codes) with
 *   seeded random values via Instancio. `false` = lean entries with only `systemId` + `_links`.
 * @property resources Per-resource overrides, keyed `domain-package-resource`
 *   (e.g. `utdanning-elev-elev`).
 */
@ConfigurationProperties("synthetic")
data class SyntheticProperties(
    val seed: Long = 1337,
    val scale: Double = 1.0,
    val pageSize: Int = 1000,
    val outputDir: String = "build/synthetic-data",
    val orgId: String = "rovari.no",
    val materializeBackLinks: Boolean = true,
    val populateFields: Boolean = true,
    val resources: Map<String, ResourceSpec> = emptyMap(),
)

/**
 * How much of one resource to generate, and which relations to wire from it.
 *
 * @property count How many entities to generate, written as the real production number.
 *   The effective amount is `count × scale`. `0` drops the resource from the run.
 * @property fixed `true` = ignore `scale` and generate exactly [count]. For code lists like
 *   kodeverk that don't grow with data volume.
 * @property relations Extra relations to generate or overrides for the automatic ones, keyed by
 *   the relation name on this resource (e.g. `elev` on elevforhold). Relations the model marks as
 *   required are wired automatically whenever the target resource is part of the run — except
 *   back-link sides that autorelation owns. The target type comes from the FINT metamodel.
 */
data class ResourceSpec(
    val count: Int? = null,
    val fixed: Boolean = false,
    val relations: Map<String, RelationSpec> = emptyMap(),
)

/**
 * How many targets each source entity links to.
 *
 * @property cardinality `"1"` = exactly one, `"0..1"` = maybe one, `"2..5"` = between two and
 *   five. Targets are picked seeded-randomly from the generated target pool. When not set, it is
 *   derived from the model's multiplicity: required relations get `1`, optional ones `0..1`.
 * @property target Only needed to point somewhere other than what the metamodel says, as
 *   `domain-package-resource`.
 */
data class RelationSpec(
    val cardinality: String? = null,
    val target: String? = null,
)
