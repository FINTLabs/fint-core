package no.fintlabs.autorelation.model

enum class RelationOperation {
    ADD,
    DELETE,
    ;

    val tagValue: String
        get() = name.lowercase()
}
