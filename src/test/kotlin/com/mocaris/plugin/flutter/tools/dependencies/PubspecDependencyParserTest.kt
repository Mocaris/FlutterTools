package com.mocaris.plugin.flutter.tools.dependencies

import com.mocaris.plugin.flutter.tools.dependencies.PubspecDependencyParser
import com.mocaris.plugin.flutter.tools.dependencies.PubspecReferences
import org.junit.*
import org.junit.Assert.*

class PubspecDependencyParserTest : com.mocaris.plugin.flutter.tools.core.YamlPsiTest() {
    @get:Rule val commandFolder = org.junit.rules.TemporaryFolder()
    private fun parse(text: String) = PubspecDependencyParser.parse(yaml(text))

    @Test fun dependenciesDevDependenciesAndComments() {
        val dependencies = parse("""
            name: sample
            description: 'http: ^99.0.0'
            dependencies:
              # a comment
              http: '^1.2.0' # retain this
              flutter:
                sdk: flutter
            dev_dependencies:
              test: any
        """)
        assertEquals(listOf("http", "flutter", "test"), dependencies.map { it.name })
        assertEquals("^1.2.0", dependencies.first().constraint)
        assertFalse(dependencies[1].isPubDev)
        assertEquals("dev_dependencies", dependencies[2].section)
    }
    @Test fun hostedAliasAndCustomSources() {
        val dependencies = parse("""
            dependencies:
              alias:
                hosted:
                  name: http
                  url: https://pub.dev
                version: ^1.0.0
              private_package:
                hosted: https://packages.example.com
                version: any
              local:
                path: ../local
              remote:
                git: https://example.com/repo.git
        """)
        assertEquals("http", dependencies[0].packageName)
        assertTrue(dependencies[0].isPubDev)
        assertEquals(listOf("custom hosted", "path", "git"), dependencies.drop(1).map { it.source })
    }
    @Test fun overridesAreEffectiveButAnchorTheOriginalDeclaration() {
        val text = """
            dependencies:
              http: ^1.0.0
            dependency_overrides:
              http:
                path: ../http
        """.trimIndent()
        val dependency = parse(text).single()
        assertTrue(dependency.overridden); assertFalse(dependency.isPubDev)
        assertEquals(text.indexOf("http:"), dependency.keyOffset)
    }
    @Test fun invalidAndDuplicateYamlIsRejected() {
        assertThrows(Exception::class.java) { parse("dependencies: [broken") }
        assertThrows(Exception::class.java) { parse("dependencies:\n  http: ^1.0.0\n  http: ^2.0.0\n") }
    }

    @Test fun packageNavigationReferencesOnlyThePackageKey() {
        val file = yaml("dependencies:\n  http: ^1.0.0\n  local:\n    path: ../local\n")
        val top = file.documents.single().topLevelValue as org.jetbrains.yaml.psi.YAMLMapping
        val dependencies = top.getKeyValueByKey("dependencies")!!.value as org.jetbrains.yaml.psi.YAMLMapping
        val http = dependencies.getKeyValueByKey("http")!!
        val reference = PubspecReferences().references(http).single() as com.intellij.openapi.paths.WebReference
        assertEquals("https://pub.dev/packages/http", reference.url)
        assertEquals("http", reference.rangeInElement.substring(http.text))
        assertTrue(PubspecReferences().references(dependencies.getKeyValueByKey("local")!!).isEmpty())
    }
    @Test fun externalOverridesReplaceSourceWithoutMovingTheOriginalAnchor() {
        val original = parse("dependencies:\n  http: ^1.0.0\n").single()
        val overrides = parse("dependency_overrides:\n  http:\n    path: ../local\n")
        val effective = PubspecDependencyParser.applyOverrides(listOf(original), overrides).single()
        assertFalse(effective.isPubDev); assertTrue(effective.overridden)
        assertEquals(original.keyOffset, effective.keyOffset)
        assertEquals(original.section, effective.section)
    }
    @Test fun versionChecksContainOnlyPublicHostedDependencies() {
        val dependencies = parse("""
            dependencies:
              remote: ^1.0.0
              pinned: 1.2.3
              relative:
                path: ../relative
              absolute:
                path: /workspace/package
              flutter:
                sdk: flutter
              fixed_git:
                git:
                  url: https://example.com/repo.git
                  ref: v1.0.0
                  path: packages/example
              private_package:
                hosted: https://packages.example.com
                version: any
            dev_dependencies:
              test: any
        """)
        assertEquals(listOf("remote", "pinned", "test"), PubspecDependencyParser.forVersionChecks(dependencies).map { it.name })
    }

    @Test fun effectiveOverridesDecideWhetherAVersionCheckIsAllowed() {
        val dependencies = parse("""
            dependencies:
              remote: ^1.0.0
              local:
                path: ../local
            dependency_overrides:
              remote:
                path: ../replacement
        """)
        assertTrue(PubspecDependencyParser.forVersionChecks(dependencies).isEmpty())
        val overrides = parse("""
            dependency_overrides:
              remote:
                git:
                  url: https://example.com/repo.git
                  ref: fixed-commit
              local: ^2.0.0
        """)
        val checked = PubspecDependencyParser.forVersionChecks(dependencies, overrides)
        assertEquals(listOf("local"), checked.map { it.name })
        assertTrue(checked.single().overridden)
        assertEquals("^2.0.0", checked.single().constraint)
    }

    private fun updateYaml(text: String, name: String = "http", latest: String = "2.3.4"): String {
        val file = com.intellij.psi.PsiFileFactory.getInstance(environment.project)
            .createFileFromText("pubspec.yaml", org.jetbrains.yaml.YAMLFileType.YML, text) as org.jetbrains.yaml.psi.YAMLFile
        val dependency = PubspecDependencyParser.parse(file).first { it.name == name }
        return DependencyVersionEditor.updatedText(file, DependencyVersionEditor.entry(file, dependency)!!, latest)
    }

    @Test fun upgradePreservesCommentsQuotesAndOtherDependencies() {
        val before = "dependencies:\n  http: '^1.0.0' # keep\n  other: 9.0.0\n"
        assertEquals(before.replace("'^1.0.0'", "'^2.3.4'"), updateYaml(before))
        assertTrue(updateYaml("dependencies:\n  http: \"1.0.0\"\n").contains("http: \"2.3.4\""))
    }

    @Test fun upgradeEditsHostedVersionWithoutChangingItsSource() {
        val before = "dependencies:\n  http:\n    hosted: https://pub.dev\n    version: ^1.0.0 # keep\n"
        assertEquals(before.replace("^1.0.0", "^2.3.4"), updateYaml(before))
        val noVersion = "dependencies:\n  http:\n    hosted: https://pub.dev # keep\n  other: any\n"
        val updated = updateYaml(noVersion)
        assertTrue(updated.contains("hosted: https://pub.dev # keep\n    version: ^2.3.4\n  other: any"))
        assertEquals("^2.3.4", parse(updated).first().constraint)
    }

    @Test fun upgradeHandlesFlowMappingsAndMissingVersions() {
        for (declaration in listOf("{hosted: https://pub.dev}", "{hosted: https://pub.dev,}")) {
            val updated = updateYaml("dependencies:\n  http: $declaration\n")
            assertEquals("^2.3.4", parse(updated).single().constraint)
        }
        assertTrue(updateYaml("dependencies:\n  http: # keep\n").contains("http: ^2.3.4 # keep"))
        assertTrue(updateYaml("dependencies:\n  http: {hosted: https://pub.dev, version:}\n").contains("version: ^2.3.4"))
    }

    @Test fun upgradeTargetsTheOverrideAndRejectsLocalSources() {
        val before = "dependencies:\n  http: ^1.0.0\ndependency_overrides:\n  http: ^1.1.0 # actual\n"
        val updated = updateYaml(before)
        assertTrue(updated.contains("http: ^1.0.0"))
        assertTrue(updated.contains("http: ^2.3.4 # actual"))
        assertThrows(IllegalArgumentException::class.java) {
            updateYaml("dependencies:\n  http: {path: ../local}\n")
        }
    }

    @Test fun updateAvailabilityNeverSuggestsDowngradesOrStaleVersions() {
        fun available(constraint: String, installed: String?, latest: String?, error: Boolean = false): Boolean {
            val dep = PackageDependency("http", section = "dependencies", constraint = constraint, source = "pub.dev")
            return DependencyVersionEditor.updateAvailable(DependencyResult(dep, installed, latest, com.mocaris.plugin.flutter.tools.core.LocalizedText("ready"), error))
        }
        assertTrue(available("^1.0.0", "1.1.0", "2.0.0"))
        assertFalse(available("^1.0.0", "2.0.0", "2.0.0"))
        assertFalse(available("^1.0.0", "3.0.0", "2.0.0"))
        assertTrue(available("^1.0.0", null, "2.0.0"))
        assertFalse(available(">=9.0.0 <10.0.0", null, "2.0.0"))
        assertFalse(available("any", null, "2.0.0"))
        assertFalse(available("^1.0.0", "1.0.0", "2.0.0", true))
        assertFalse(available("^1.0.0", "1.0.0", null))
    }

    @Test fun pubGetUsesProjectSdkAndFvmInsteadOfAnUnrelatedPathSdk() {
        val root = commandFolder.newFolder("project").toPath()
        val sdk = commandFolder.newFolder("sdk").toPath()
        java.nio.file.Files.createDirectories(sdk.resolve("packages/flutter"))
        java.nio.file.Files.createDirectories(sdk.resolve("bin"))
        java.nio.file.Files.writeString(sdk.resolve("bin/flutter"), "executable")
        val config = root.resolve(".dart_tool/package_config.json")
        java.nio.file.Files.createDirectories(config.parent)
        java.nio.file.Files.writeString(config, """{"packages":[{"name":"flutter","rootUri":"${sdk.resolve("packages/flutter").toUri()}"}]}""")
        assertEquals(sdk.resolve("bin/flutter").toString(), FlutterPubGetRunner.executable(root, false))
        val fvm = root.resolve(".fvm/flutter_sdk/bin/flutter")
        java.nio.file.Files.createDirectories(fvm.parent)
        java.nio.file.Files.writeString(fvm, "executable")
        assertEquals(fvm.toString(), FlutterPubGetRunner.executable(root, false))
        assertEquals("flutter", FlutterPubGetRunner.executable(commandFolder.newFolder("empty").toPath(), false))
    }

    @Test fun versionUnderlineTargetsTheScalarAndExcludesComments() {
        val file = yaml("dependencies:\n  http:\n    hosted: https://pub.dev\n    version: '^1.0.0' # retain\n")
        val dep = PubspecDependencyParser.parse(file).single()
        val range = DependencyVersionEditor.versionRange(DependencyVersionEditor.entry(file, dep)!!)!!
        assertEquals("'^1.0.0'", range.substring(file.text))
    }

}
