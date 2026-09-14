pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}

rootProject.name = "Kasha"

// Logical Gradle IDs stay stable while the physical repository is grouped by responsibility.
include(
    ":kashaCore",
    ":aiCatalog",
    ":aiConnectors",
    ":composeApp",
    ":runtime",
    ":desktopApp",
    ":androidApp",
    ":iosShell",
    ":webApp",
)

project(":kashaCore").projectDir = file("modules/core")
project(":aiCatalog").projectDir = file("modules/ai/catalog")
project(":aiConnectors").projectDir = file("modules/ai/connectors")
project(":composeApp").projectDir = file("modules/ui")
project(":runtime").projectDir = file("modules/infrastructure/jvm")
project(":desktopApp").projectDir = file("platforms/desktop")
project(":androidApp").projectDir = file("platforms/android")
project(":iosShell").projectDir = file("platforms/ios/shared")
project(":webApp").projectDir = file("platforms/web")
