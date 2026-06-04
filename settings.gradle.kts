rootProject.name = "fint-core"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://repo.fintlabs.no/releases") }
        mavenCentral()
    }
}

include("fint-core-consumer", "fint-core-provider-gateway", "fint-core-resource-store")
