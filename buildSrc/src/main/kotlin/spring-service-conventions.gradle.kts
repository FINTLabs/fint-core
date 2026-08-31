plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlin.plugin.lombok")
    id("org.jlleitschuh.gradle.ktlint")
    java
}

group = "no.fintlabs"
version = System.getenv("RELEASE_VERSION") ?: "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-configuration-processor")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")
    implementation("no.fintlabs:fint-core-infra-models:2.1.0")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    implementation("no.novari:fint-core-information-model:0.6.0")
    implementation("no.novari:kafka:6.0.0")
}

testing {
    suites {
        withType<JvmTestSuite>().configureEach {
            useJUnitJupiter()
            dependencies {
                implementation("io.mockk:mockk:1.13.13")
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.jetbrains.kotlin:kotlin-test")
                implementation("org.springframework.kafka:spring-kafka-test")
                implementation("org.testcontainers:junit-jupiter")
            }
        }
    }
}

ktlint {
    version.set("1.8.0")
}

tasks.named("check") {
    dependsOn("ktlintCheck")
}
