package no.novari.core.shared.model

class ResourceRef(
    val domainName: String,
    val packageName: String,
    val resourceName: String,
) {
    fun toURI() = "$domainName/$packageName/$resourceName"
}
