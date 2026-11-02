// Maven Central is rate limited from some networks (the cloud sandbox). Set MAVEN_CENTRAL_MIRROR to use another copy of it.
val mavenCentralUrl = System.getenv("MAVEN_CENTRAL_MIRROR") ?: "https://repo.maven.apache.org/maven2"

pluginManagement {
    val mirror = System.getenv("MAVEN_CENTRAL_MIRROR")
    repositories {
        google()
        if (mirror != null) maven(mirror) else mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven(mavenCentralUrl)
    }
}

rootProject.name = "lock-me-in"
include(":core")
