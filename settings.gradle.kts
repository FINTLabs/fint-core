rootProject.name = "fint-core"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenLocal()
        mavenCentral()
        maven { url = uri("https://repo.fintlabs.no/releases") }
    }
}

include("fint-core-client-api", "fint-core-provider-gateway", "fint-core-shared")
