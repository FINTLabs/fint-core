package no.novari.core.shared.event

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.model.resourceRefOf
import no.novari.fint.core.model.FintResourceRef

fun RequestFintEvent.toResourceRef(): FintResourceRef = resourceRefOf(domainName, packageName, resourceName)

fun RequestFintEvent.toCoordinate(): ResourceCoordinate = ResourceCoordinate(orgId, domainName, packageName, resourceName)
