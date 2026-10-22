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

    // Claude translation provider. The IDE provides the Kotlin stdlib and kotlin-reflect the SDK needs (1.9+).
    implementation("com.anthropic:anthropic-java:2.75.0") {
        exclude(group = "org.jetbrains.kotlin")
    }

    intellijPlatform {
        if (localIde != null) local(localIde) else intellijIdea(providers.gradleProperty("translationshub.ciPlatformVersion").get())
        // Required (bundled in every supported IDE): extracting hardcoded strings needs the Kotlin PSI.
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
        zipSigner()
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

/** The newest section of CHANGELOG.md as HTML, for the Marketplace "What's New" tab. */
val changeNotesHtml = providers.fileContents(rootProject.layout.projectDirectory.file("CHANGELOG.md")).asText.map { md ->
    val section = md.substringAfter("\n## ").substringAfter("\n").substringBefore("\n## ").trim()
    val items = section.lines().filter { it.startsWith("- ") }.joinToString("") { "<li>${it.removePrefix("- ")}</li>" }
    "<ul>$items</ul>"
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false

    pluginConfiguration {
        // Permanent once published on Marketplace.
        id = "io.github.khubaibkhan4.kmptranslationshub"
        name = "KMP Translations Hub"
        version = project.version.toString()
        changeNotes = changeNotesHtml
        vendor {
            name = providers.gradleProperty("translationshub.vendorName")
            url = providers.gradleProperty("translationshub.vendorUrl")
        }
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
        // Freemium plan: only once JetBrains Marketplace has approved it and assigned the product code, e.g.
        // -Ptranslationshub.productCode=PKMPTRANSLATIONS. Without it the build is free and every Pro feature is on.
        providers.gradleProperty("translationshub.productCode").orNull?.let { productCode ->
            productDescriptor {
                code = productCode
                releaseDate = providers.gradleProperty("translationshub.releaseDate")
                releaseVersion = providers.gradleProperty("translationshub.releaseVersion")
                optional = true
            }
        }
    }

    // ./gradlew :idea-plugin:signPlugin — values come from CI secrets, never from files in the repo.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // ./gradlew :idea-plugin:publishPlugin — needs a JetBrains Marketplace permanent token.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("translationshub.marketplaceChannel").map { listOf(it) }
    }

    pluginVerification {
        ides {
            if (localIde != null) local(localIde)
            if (providers.gradleProperty("translationshub.verifyRecommended").orNull == "true") recommended()
            // e.g. -Ptranslationshub.verifyIdes=IU:2025.1.7.2,IC:2025.2.6 to reproduce a Marketplace verification report.
            providers.gradleProperty("translationshub.verifyIdes").orNull?.split(',')?.filter { it.isNotBlank() }?.forEach {
                val (type, version) = it.trim().split(':')
                create(type, version)
            }
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
    // Pro features are on in a build without a product code; the tests that check the free tier turn them off.
    providers.gradleProperty("translationshub.pro").orNull?.let { systemProperty("translationshub.pro", it) }
    // -Ptranslationshub.live=true also runs the tests that call real translation services (MyMemory, no key).
    providers.gradleProperty("translationshub.live").orNull?.let { systemProperty("translationshub.live", it) }
}

tasks.named<Zip>("buildPlugin") {
    archiveBaseName.set("kmp-translations-hub")
}
