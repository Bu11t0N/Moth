pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    resolutionStrategy { eachPlugin {
        if (requested.id.id == "com.android.application") useModule("com.android.tools.build:gradle:${requested.version}")
        if (requested.id.id.startsWith("org.jetbrains.kotlin")) useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
    } }
}
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "Moth"
include(":core", ":app", ":e2e-helper")

