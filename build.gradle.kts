plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "org.mocaris.plugin"
version = "1.0.12"

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
    intellijPlatform {
        create("IC", "2023.2")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add necessary plugin dependencies for compilation here, example:
        // bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "211"
        }

        changeNotes = """
        ## 1.0.12 
        - update plugin and use yaml config

        ## 1.0.11 
        - bug fixed,Using the Notification Api 

        ## 1.0.10 
        - update plugin 

        ## 1.0.9 
        - bug fixed 

        ## 1.0.8 
        - sort sync files 

        ## 1.0.7 
        - fixed window path bug 
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "11"
        targetCompatibility = "11"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}
