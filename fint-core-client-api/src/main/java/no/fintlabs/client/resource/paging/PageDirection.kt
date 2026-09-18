package no.fintlabs.client.resource.paging

enum class PageDirection(
    val code: Byte,
) {
    AFTER(1),
    BEFORE(2),
}
