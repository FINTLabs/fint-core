rootProject.name = "fint-felleskomponent"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://repo.fintlabs.no/releases") }
        mavenCentral()
    }
}

include("fint-core-consumer", "fint-core-provider-gateway")
