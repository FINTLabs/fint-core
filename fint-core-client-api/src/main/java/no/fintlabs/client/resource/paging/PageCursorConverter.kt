package no.fintlabs.client.resource.paging

import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class PageCursorConverter(
    private val cursorCodec: PageCursorCodec,
) : Converter<String, PageCursor> {
    override fun convert(source: String): PageCursor = cursorCodec.decode(source)
}
