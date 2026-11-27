plugins {
    `kotlin-dsl`
}

dependencyLocking {
    lockAllConfigurations()
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-gradle-plugin:4.2.0")
    implementation("io.spring.gradle:dependency-management-plugin:1.1.7")

    implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:2.4.10")
    implementation("org.jetbrains.kotlin.plugin.spring:org.jetbrains.kotlin.plugin.spring.gradle.plugin:2.4.10")
    implementation("org.jetbrains.kotlin.plugin.lombok:org.jetbrains.kotlin.plugin.lombok.gradle.plugin:2.4.10")
    implementation("org.jlleitschuh.gradle:ktlint-gradle:14.2.0")

    constraints {
        implementation("org.apache.commons:commons-lang3:3.22.0") {
            because("Versions before 3.18.0 contain a known vulnerability")
        }
    }
}
