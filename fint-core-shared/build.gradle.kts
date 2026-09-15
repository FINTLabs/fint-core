plugins {
    id("spring-service-conventions")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter")

    testImplementation("org.testcontainers:testcontainers-mongodb")
}

tasks.named("bootJar") { enabled = false }
tasks.named("jar") { enabled = true }

tasks.test {
    (project.findProperty("benchmark.documents") as String?)?.let { systemProperty("benchmark.documents", it) }
}
