package no.novari.fint.core.consumer.security.opa

import com.fasterxml.jackson.annotation.JsonInclude

data class OpaRequest(
    val input: OpaInput,
) {
    constructor(
        username: String,
        env: String,
        domainName: String,
        packageName: String,
        resourceName: String?,
    ) : this(OpaInput(username, env, domainName, packageName, resourceName))
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class OpaInput(
    val username: String,
    val env: String,
    val domainName: String,
    val packageName: String,
    val resourceName: String?,
)

data class OpaResponse(
    val result: OpaResult = OpaResult(),
)

data class OpaResult(
    val allow: Boolean = false,
    val fields: Set<String> = emptySet(),
    val relations: Set<String> = emptySet(),
)
