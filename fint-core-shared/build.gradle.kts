plugins {
    id("spring-service-conventions")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
}

tasks.named("bootJar") { enabled = false }
tasks.named("jar") { enabled = true }
