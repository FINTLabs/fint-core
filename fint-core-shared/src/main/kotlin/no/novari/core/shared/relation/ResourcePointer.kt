package no.novari.core.shared.relation

data class ResourcePointer(
    val orgId: String,
    val domainName: String,
    val packageName: String,
    val resourceName: String,
    val idField: String,
    val idValue: String,
) {
    fun key(): String = "$orgId|$domainName|$packageName|$resourceName|${idField.lowercase()}|$idValue"

    fun href(): String = "${idField.lowercase()}/$idValue"
}
