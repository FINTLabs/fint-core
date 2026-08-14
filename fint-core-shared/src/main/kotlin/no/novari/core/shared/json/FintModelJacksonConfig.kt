package no.novari.core.shared.json

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
open class FintModelJacksonConfig {
    @Bean
    open fun fintModelModule(): FintModelModule = FintModelModule()
}
