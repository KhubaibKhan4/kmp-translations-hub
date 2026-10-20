pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kmp-translations-hub"

// Dependency-free engine: strings.xml model, locale qualifiers, plural rules, checks and the translation lock file.
include(":core")
if (providers.gradleProperty("translationshub.skipIdea").orNull != "true") include(":idea-plugin")
