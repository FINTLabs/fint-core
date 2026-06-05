package no.novari.fint.core.provider.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

@Configuration
@EnableAsync
class AsyncConfig {
    @Bean("evictionTaskExecutor")
    fun evictionTaskExecutor(): Executor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 2
            maxPoolSize = 4
            queueCapacity = 1_000
            threadNamePrefix = "provider-eviction-"
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(30)
            initialize()
        }
}
