package no.fintlabs.consumer.config

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import no.fintlabs.adapter.models.event.ResponseFintEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

@Configuration
open class CaffeineCacheConfig {
    @Bean
    open fun stringCache(): Cache<String, String> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(4, TimeUnit.HOURS)
            .build()

    @Bean
    open fun responseFintEvents(): Cache<String, ResponseFintEvent> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(4, TimeUnit.HOURS)
            .build()
}
