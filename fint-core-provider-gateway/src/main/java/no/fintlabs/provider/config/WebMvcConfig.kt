package no.fintlabs.provider.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.filter.UrlHandlerFilter

@Configuration(proxyBeanMethods = false)
class WebMvcConfig {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    fun trailingSlashFilter(): UrlHandlerFilter =
        UrlHandlerFilter
            .trailingSlashHandler("/**")
            .wrapRequest()
            .build()
}
