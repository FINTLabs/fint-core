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

    implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:2.4.20")
    implementation("org.jetbrains.kotlin.plugin.spring:org.jetbrains.kotlin.plugin.spring.gradle.plugin:2.4.20")
    implementation("org.jetbrains.kotlin.plugin.lombok:org.jetbrains.kotlin.plugin.lombok.gradle.plugin:2.4.20")
    implementation("org.jlleitschuh.gradle:ktlint-gradle:14.2.0")

    constraints {
        implementation("org.apache.commons:commons-lang3:3.18.0") {
            because("Versions before 3.18.0 contain a known vulnerability")
        }
    }
}
