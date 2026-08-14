plugins {
    id("spring-service-conventions")
}

dependencies {
    implementation(project(":fint-core-shared"))

    implementation("no.fintlabs:fint-antlr:1.1.1")
    implementation("no.fintlabs:fint-core-status-models:1.0.0")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    implementation("com.google.guava:guava:33.5.0-jre")

    testImplementation("org.testcontainers:kafka")
    testImplementation("org.awaitility:awaitility-kotlin:4.3.0")
}
