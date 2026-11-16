import org.jetbrains.changelog.markdownToHTML
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
    id("org.jetbrains.changelog") version "2.2.1"
}

group = "org.mocaris.plugin"
version = "2.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    implementation("org.yaml:snakeyaml:2.2")
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        val localIdePath = providers.gradleProperty("localIdePath").orNull
        if (localIdePath.isNullOrBlank()) create("IC", "2023.2") else local(localIdePath)
        bundledPlugin("org.jetbrains.plugins.yaml")
        // IntelliJ 2023.2 includes its test framework in the SDK distribution.
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Bundled)

        // Add necessary plugin dependencies for compilation here, example:
        // bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "232"
        }
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
tasks.patchPluginXml {
    pluginDescription.set(markdownToHTML(File(rootDir, "pluginDescription.md").readText()))
    changeNotes.set(markdownToHTML(File(rootDir, "changeNotes.md").readText()))
}
