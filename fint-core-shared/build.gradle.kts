plugins {
    id ("java")
    id ("io.spring.dependency-management")
    id ("org.springframework.boot")
    id ("org.jetbrains.kotlin.jvm")
    id ("org.jetbrains.kotlin.plugin.spring")
    id ("org.jlleitschuh.gradle.ktlint")
}

group = "no.novari"
version = "0.0.1-SNAPSHOT"


java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    jvmToolchain(25)
}




dependencies {
    api ("no.novari:fint-model-resource:1.0.1")
    api ("no.novari:fint-model-core:1.0.0")
    api ("org.springframework.boot:spring-boot-starter-data-mongodb")
    api ("org.springframework.kafka:spring-kafka")

    implementation ("org.springframework:spring-web")
    implementation ("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation ("no.fintlabs:fint-antlr:1.1.1")
    implementation ("org.reflections:reflections:0.10.2")

    // Adapter models (SyncType etc.) are provided at runtime by the host app (consumer/provider),
    // which pin their own version; compile against a known one here.
    compileOnly ("no.fintlabs:fint-core-infra-models:2.1.0")

    testImplementation ("org.jetbrains.kotlin:kotlin-test")
    testImplementation ("org.testcontainers:mongodb")
}

ktlint {
    version = "1.8.0"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
