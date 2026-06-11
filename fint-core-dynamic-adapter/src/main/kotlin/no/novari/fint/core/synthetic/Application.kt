package no.novari.fint.core.synthetic

import no.novari.fint.core.shared.autorelation.cache.RelationRuleBuilder
import no.novari.fint.core.shared.autorelation.cache.RelationRuleRegistry
import no.novari.metamodel.ComponentBuilder
import no.novari.metamodel.MetamodelService
import no.novari.metamodel.ReflectionService
import org.reflections.Reflections
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import kotlin.system.exitProcess

@SpringBootApplication
@ConfigurationPropertiesScan
@Import(
    ReflectionService::class,
    ComponentBuilder::class,
    MetamodelService::class,
    RelationRuleBuilder::class,
    RelationRuleRegistry::class,
)
class Application {
    @Bean
    fun reflections(): Reflections = Reflections("no.novari.fint.model")
}

fun main(args: Array<String>) {
    exitProcess(SpringApplication.exit(runApplication<Application>(*args)))
}
