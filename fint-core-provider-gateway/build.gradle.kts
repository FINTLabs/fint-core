plugins {
    id("spring-service-conventions")
    groovy
    id("com.github.ben-manes.versions") version "0.53.0"
    id("se.patrikerdes.use-latest-versions") version "0.2.18"
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    implementation("jakarta.annotation:jakarta.annotation-api:3.0.0-M1")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    implementation("no.fint:fint-event-model:3.0.2")
    implementation("no.novari:fint-core-principal:4.1.0")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.security:spring-security-test")

    "integrationTestImplementation"("org.springframework.boot:spring-boot-testcontainers")
    "integrationTestImplementation"("org.testcontainers:postgresql")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    enabled = false
}

tasks.register<JavaExec>("benchmarkKafkaProducer") {
    group = "verification"
    description = "Run Kafka producer benchmark against a local Kafka broker"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "no.fintlabs.provider.performance.KafkaProducerBenchmark"

    val benchmarkProperties = System.getProperties()
        .entries
        .filter { it.key.toString().startsWith("benchmark.") }
    benchmarkProperties.forEach { systemProperty(it.key.toString(), it.value.toString()) }

    project.properties
        .entries
        .filter { it.key.startsWith("benchmark.") }
        .forEach { systemProperty(it.key, it.value.toString()) }
}
