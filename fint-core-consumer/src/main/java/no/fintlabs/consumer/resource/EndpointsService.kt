package no.fintlabs.consumer.resource

import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.config.EndpointsConstants
import no.novari.fint.core.model.FintModel
import org.springframework.stereotype.Service

data class ResourceEndpointsDto(
    val lastUpdatedUrl: String,
    val cacheSizeUrl: String,
    val collectionUrl: String,
    val oneUrl: List<String>,
)

typealias ResourceEndpointsResponse = Map<String, ResourceEndpointsDto>

@Service
class EndpointsService(
    private val consumerConfiguration: ConsumerConfiguration,
) {
    /**
     * Gives an overview of the available endpoints in a certain FINT-component.
     * Example:
     * {
     *      "karakterverdi": {
     * 		    "lastUpdatedUrl": "https://beta.felleskomponent.no/utdanning/vurdering/karakterverdi/last-updated",
     * 		    "cacheSizeUrl": "https://beta.felleskomponent.no/utdanning/vurdering/karakterverdi/cache/size",
     * 		    "collectionUrl": "https://beta.felleskomponent.no/utdanning/vurdering/karakterverdi",
     * 		    "oneUrl": [
     * 			    "https://beta.felleskomponent.no/utdanning/vurdering/karakterverdi/systemid/{id:.+}"
     * 		    ]
     * 	    },
     *  	"eksamensgruppe": {
     * 	    	"lastUpdatedUrl": "https://beta.felleskomponent.no/utdanning/vurdering/eksamensgruppe/last-updated",
     * 		    "cacheSizeUrl": "https://beta.felleskomponent.no/utdanning/vurdering/eksamensgruppe/cache/size",
     * 	    	"collectionUrl": "https://beta.felleskomponent.no/utdanning/vurdering/eksamensgruppe",
     * 		    "oneUrl": [
     * 			    "https://beta.felleskomponent.no/utdanning/vurdering/eksamensgruppe/systemid/{id:.+}"
     * 	    	]
     *   	},
     *   ...(and so forth for whole package)
     * }
     *
     */
    fun componentOverview(
        domainName: String,
        packageName: String,
    ): ResourceEndpointsResponse =
        FintModel.refsIn(domainName, packageName).associate { resourceRef ->
            val metadata =
                requireNotNull(
                    FintModel.byPath(
                        resourceRef.domainName,
                        resourceRef.packageName,
                        resourceRef.resourceName,
                    ),
                )

            val resourcePath =
                requireNotNull(metadata.pathIn("$domainName/$packageName"))

            val collectionUrl =
                "${consumerConfiguration.baseUrl}/$resourcePath"

            resourceRef.resourceName to
                ResourceEndpointsDto(
                    lastUpdatedUrl = collectionUrl + EndpointsConstants.LAST_UPDATED,
                    cacheSizeUrl = collectionUrl + EndpointsConstants.CACHE_SIZE,
                    collectionUrl = collectionUrl,
                    oneUrl = metadata.idFields.map { idField -> "$collectionUrl/${idField.lowercase()}/{id:.+}" },
                )
        }
}
