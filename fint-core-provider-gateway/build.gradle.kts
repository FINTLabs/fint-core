plugins {
    id("spring-service-conventions")
    groovy
    id("com.github.ben-manes.versions") version "0.61.0"
    id("se.patrikerdes.use-latest-versions") version "0.2.19"
}

dependencies {
    implementation(project(":fint-core-shared"))

    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    implementation("jakarta.annotation:jakarta.annotation-api:3.0.0-M1")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    implementation("no.fint:fint-event-model:3.0.2")
    implementation("no.novari:fint-core-principal:4.1.0")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:mongodb")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.awaitility:awaitility-kotlin:4.3.0")

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

    val benchmarkProperties =
        System
            .getProperties()
            .entries
            .filter { it.key.toString().startsWith("benchmark.") }
    benchmarkProperties.forEach { systemProperty(it.key.toString(), it.value.toString()) }

    project.properties
        .entries
        .filter { it.key.startsWith("benchmark.") }
        .forEach { systemProperty(it.key, it.value.toString()) }
}
