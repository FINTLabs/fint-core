package no.novari.fint.core.synthetic.graph

import no.novari.fint.core.shared.autorelation.model.EntityDescriptor

/**
 * The result of a generator run: the entity graph plus the findings collected while wiring it
 * (required relations that could not be wired, target pools that ran dry). The notes end up in
 * `manifest.json` so a run can be audited — and fed back to adjust the dataset.
 */
data class Generation(
    val entities: Map<EntityDescriptor, List<SyntheticEntity>>,
    val notes: List<String>,
)

fun EntityDescriptor.key(): String = "${domainName}_${packageName}_$resourceName"
