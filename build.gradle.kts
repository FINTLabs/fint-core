plugins {
    id("org.springframework.boot") version "3.5.12" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.spring") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.lombok") version "2.3.21" apply false
    id("org.jlleitschuh.gradle.ktlint") version "13.1.0" apply false
    id("com.github.ben-manes.versions") version "0.53.0" apply false
    id("se.patrikerdes.use-latest-versions") version "0.2.18" apply false
}

subprojects {
    pluginManager.withPlugin("java") {
        dependencies {
            "implementation"("org.springframework.boot:spring-boot-starter-actuator")
            "implementation"("org.springframework.kafka:spring-kafka")
            "implementation"("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
            "implementation"("com.github.ben-manes.caffeine:caffeine:3.2.3")
            "implementation"("no.novari:kafka:6.0.0")
            "implementation"("no.novari:fint-core-metamodel:3.0.0")

            "compileOnly"("org.projectlombok:lombok")
            "annotationProcessor"("org.projectlombok:lombok")

            "runtimeOnly"("io.micrometer:micrometer-registry-prometheus")

            "testImplementation"("io.mockk:mockk:1.13.13")
            "testImplementation"("org.springframework.boot:spring-boot-starter-test")
            "testImplementation"("org.springframework.kafka:spring-kafka-test")
            "testImplementation"("org.testcontainers:junit-jupiter")
        }
    }
}
