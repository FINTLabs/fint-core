package no.fintlabs.consumer.config

import org.apache.tomcat.util.buf.EncodedSolidusHandling
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Id values may contain a slash, which link rendering encodes as `%2F` — and Tomcat rejects any
 * URI with an encoded slash (400) by default. Pass-through keeps the value encoded through
 * routing, so it stays one path segment; Spring decodes it when binding the `idValue` path
 * variable. Tomcat's default guards apps that authorize by path structure, which this API never
 * does — access is decided by the JWT and the org-id header.
 */
@Configuration
open class TomcatConfiguration {
    @Bean
    open fun allowEncodedSlashes(): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        WebServerFactoryCustomizer { factory ->
            factory.addConnectorCustomizers(
                TomcatConnectorCustomizer { connector ->
                    connector.encodedSolidusHandling = EncodedSolidusHandling.PASS_THROUGH.value
                },
            )
        }
}
