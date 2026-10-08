plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // When spring boot is updated from 4.1.1, review and remove "extra" pins and constraints!
    implementation("org.springframework.boot:spring-boot-gradle-plugin:4.1.1")
    implementation("io.spring.gradle:dependency-management-plugin:1.1.7")

    implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:2.4.10")
    implementation("org.jetbrains.kotlin.plugin.spring:org.jetbrains.kotlin.plugin.spring.gradle.plugin:2.4.10")
    implementation("org.jetbrains.kotlin.plugin.lombok:org.jetbrains.kotlin.plugin.lombok.gradle.plugin:2.4.10")
    implementation("org.jlleitschuh.gradle:ktlint-gradle:14.2.0")

    constraints {
        implementation("org.apache.commons:commons-lang3:3.18.0") {
            because("Versions before 3.18.0 contain a known vulnerability")
        }
        // Keep these constraints alongside the jackson-bom.version override in
        // spring-service-conventions.gradle.kts. The "extra" manages application dependencies,
        // while these constraints secure buildSrc's separate Spring Boot plugin classpath.
        implementation("tools.jackson.core:jackson-databind:3.1.7") {
            because("Versions before 3.1.6 contain a known vulnerability")
        }
        implementation("tools.jackson.core:jackson-core:3.1.7") {
            because("Versions before 3.1.6 contain a known vulnerability")
        }
    }
}
