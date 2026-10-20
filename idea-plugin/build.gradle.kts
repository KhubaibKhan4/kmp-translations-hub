import java.util.Properties
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        // 2025.1 (since-build 251) bundles Kotlin 2.1; stay at or below it.
        apiVersion = KotlinVersion.KOTLIN_2_1
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// A local Android Studio / IntelliJ (from -P, ~/.gradle/gradle.properties or the git-ignored local.properties),
// otherwise IntelliJ IDEA is downloaded (CI).
val localIdePath: String? = providers.gradleProperty("translationshub.ideaLocalPath").orNull
    ?: rootProject.file("local.properties").takeIf { it.isFile }?.let { file ->
        Properties().apply { file.reader().use { load(it) } }.getProperty("translationshub.ideaLocalPath")
    }
val localIde: String? = localIdePath?.takeIf { file(it).exists() }

dependencies {
    implementation(project(":core")) {
        // The IDE provides the Kotlin stdlib.
        exclude(group = "org.jetbrains.kotlin")
    }

    intellijPlatform {
        if (localIde != null) local(localIde) else intellijIdea(providers.gradleProperty("translationshub.ciPlatformVersion").get())
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
        zipSigner()
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false

    pluginConfiguration {
        // Permanent once published on Marketplace.
        id = "io.github.khubaibkhan4.kmptranslationshub"
        name = "KMP Translations Hub"
        version = project.version.toString()
        vendor {
            name = providers.gradleProperty("translationshub.vendorName")
            url = providers.gradleProperty("translationshub.vendorUrl")
        }
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            if (localIde != null) local(localIde)
            if (providers.gradleProperty("translationshub.verifyRecommended").orNull == "true") recommended()
        }
    }
}

// The IntelliJ Platform plugin derives jvmTarget from the IDE's runtime (Java 25 for 2026.2).
// Keep 21 so the plugin also loads in 2025.x IDEs that run on JBR 21.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    compilerOptions.jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
}

// ./gradlew :idea-plugin:runIde -Ptranslationshub.openProject=/path/to/project opens that project in the sandbox IDE.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    val openProject = providers.gradleProperty("translationshub.openProject")
    argumentProviders += CommandLineArgumentProvider { listOfNotNull(openProject.orNull) }
}

tasks.named<Test>("test") {
    maxHeapSize = "2g"
}

tasks.named<Zip>("buildPlugin") {
    archiveBaseName.set("kmp-translations-hub")
}
