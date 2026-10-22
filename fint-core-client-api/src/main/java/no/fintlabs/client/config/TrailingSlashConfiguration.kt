package no.fintlabs.client.config

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.filter.UrlHandlerFilter

@Configuration
class TrailingSlashConfiguration {
    /** Serves a path that ends with a slash as if the slash was not there. */
    @Bean
    fun trailingSlashFilter(): FilterRegistrationBean<UrlHandlerFilter> =
        FilterRegistrationBean(
            // wraps the request instead of redirecting, because not every client follows a redirect
            UrlHandlerFilter.trailingSlashHandler("/**").wrapRequest().build(),
        ).apply {
            // runs before security, so its path rules see the path without the slash
            order = Ordered.HIGHEST_PRECEDENCE
        }
}
