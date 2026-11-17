package com.mocaris.plugin.flutter.tools.assets

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class AssetsOutputTest {
    @get:Rule val folder = TemporaryFolder()
    private val root get() = folder.root.toPath()

    private fun file(path: String, content: String = "resource"): Path = root.resolve(path).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, content)
    }

    private fun generate(config: AssetsSyncConfig = AssetsSyncConfig(), yaml: String = "name: sample") =
        AssetsGenerator.generate(root.toString(), config, yaml)

    private fun write(contents: Map<Path, String>, obsolete: Set<Path> = emptySet()) {
        val prepared = AssetsOutputWriter.prepare(contents)
        try { AssetsOutputWriter.publish(contents, prepared, obsolete) }
        finally { AssetsOutputWriter.cleanup(prepared) }
    }

    @Test fun changingGenerationDirectoryUpdatesReferencesAndMigratesPreviousParts() {
        var previous = emptySet<Path>()
        for (directory in listOf(DEFAULT_GENERATE_DIR, "lib/custom", "lib/images ${'$'}tmp", "lib")) {
            val config = AssetsSyncConfig(generateDir = directory)
            val contents = generate(config)
            val obsolete = AssetsGenerator.legacyOutputPaths(root, config)
            assertEquals(previous - contents.keys, obsolete)
            val entry = root.resolve("lib/r.dart")
            val parts = contents.keys - setOf(entry)
            assertTrue(parts.all { it.parent == root.resolve(directory) })
            write(contents, obsolete)
            assertTrue(obsolete.none(Files::exists))
            for (part in parts) {
                val reference = AssetsGenerator.escapeDart(entry.parent.relativize(part).toString())
                assertTrue(Files.readString(entry).contains("part \"$reference\";"))
                assertTrue(AssetsOutputWriter.isOutputEvent(part.toString(), contents.keys))
            }
            val scanned = generate(config, "name: sample\nflutter:\n  assets: [$directory/]\n")
            assertFalse(scanned.getValue(root.resolve("$directory/assets.g.dart")).contains("ResourceMeta("))
            previous = parts
        }
    }

    @Test fun generationDirectoryRejectsOutsidePathsAndExistingFiles() {
        file("lib/not-a-directory")
        for (directory in listOf("../outside", "/tmp/outside", "", ".", "lib/not-a-directory")) {
            assertThrows(IllegalArgumentException::class.java) { generate(AssetsSyncConfig(generateDir = directory)) }
        }
    }

    @Test fun defaultsGenerateEntryAndSeparateResourceGroups() {
        file("assets/logo.png"); file("fonts/demo.ttf"); file("shaders/effect.frag")
        val contents = generate(yaml = """
            name: sample
            flutter:
              assets: [assets/logo.png]
              fonts:
                - family: Demo
                  fonts: [{asset: fonts/demo.ttf}]
              shaders: [shaders/effect.frag]
        """.trimIndent())
        assertEquals(listOf("assets.g.dart", "fonts.g.dart", "shaders.g.dart", "r.dart"), contents.keys.map { it.fileName.toString() })
        val entry = contents.getValue(root.resolve("lib/r.dart"))
        assertTrue(entry.contains("abstract class R"))
        listOf("assets", "fonts", "shaders").forEach { name ->
            assertTrue(entry.contains("part \"generate/$name.g.dart\";"))
            assertTrue(entry.contains("static const $name = _R${name.replaceFirstChar { it.uppercaseChar() }}();"))
            assertTrue(contents.getValue(root.resolve("lib/generate/$name.g.dart")).contains("part of \"../r.dart\";"))
        }
        val assets = contents.getValue(root.resolve("lib/generate/assets.g.dart"))
        val fonts = contents.getValue(root.resolve("lib/generate/fonts.g.dart"))
        val shaders = contents.getValue(root.resolve("lib/generate/shaders.g.dart"))
        assertTrue(assets.contains("final RResource logo = const RResource(RResourceMeta(\"sample\", \"assets/logo.png\", RResourceKind.assets));"))
        assertFalse(assets.contains("fonts/demo.ttf")); assertFalse(assets.contains("shaders/effect.frag"))
        assertTrue(fonts.contains("fonts/demo.ttf")); assertFalse(fonts.contains("assets/logo.png"))
        assertTrue(shaders.contains("shaders/effect.frag")); assertFalse(shaders.contains("fonts/demo.ttf"))
        write(contents)
        contents.forEach { (path, content) -> assertEquals(content, Files.readString(path)) }
    }

    @Test fun customEntryKeepsPartsInLibGenerateWithRelativeReferences() {
        val contents = generate(AssetsSyncConfig(outPath = "lib/generated/resources.dart", outClass = "Resources"))
        assertTrue(contents.keys.toList().dropLast(1).all { it.parent == root.resolve("lib/generate") })
        val entry = contents.getValue(root.resolve("lib/generated/resources.dart"))
        assertTrue(entry.contains("static const assets = _ResourcesAssets();"))
        assertTrue(entry.contains("part \"../generate/assets.g.dart\";"))
        assertTrue(contents.getValue(root.resolve("lib/generate/fonts.g.dart")).contains("part of \"../generated/resources.dart\";"))
    }

    @Test fun emptyGroupsReplaceStaleConstants() {
        file("assets/logo.png")
        write(generate(yaml = "name: sample\nflutter:\n  assets: [assets/logo.png]\n"))
        write(generate())
        assertFalse(Files.readString(root.resolve("lib/generate/assets.g.dart")).contains("logo"))
        assertTrue(Files.readString(root.resolve("lib/generate/assets.g.dart")).contains("const _RAssets();"))
    }

    @Test fun conflictingPartPreventsAllReplacements() {
        val contents = generate()
        write(contents)
        val protected = file("lib/generate/fonts.g.dart", "user content")
        val updated = contents.mapValues { (_, content) -> content + "// changed\n" }
        assertThrows(IllegalArgumentException::class.java) { write(updated) }
        assertEquals("user content", Files.readString(protected))
        contents.filterKeys { it != protected }.forEach { (path, content) -> assertEquals(content, Files.readString(path)) }
    }

    @Test fun conflictCreatedAfterPreparationPreventsAllReplacements() {
        val contents = generate()
        val prepared = AssetsOutputWriter.prepare(contents)
        try {
            file("lib/generate/shaders.g.dart", "user content")
            assertThrows(IllegalArgumentException::class.java) { AssetsOutputWriter.publish(contents, prepared) }
            assertFalse(Files.exists(root.resolve("lib/generate/assets.g.dart")))
            assertFalse(Files.exists(root.resolve("lib/r.dart")))
        } finally { AssetsOutputWriter.cleanup(prepared) }
    }

    @Test fun failedReplacementRollsBackEarlierOutputs() {
        val contents = generate()
        write(contents)
        val updated = contents.mapValues { (_, content) -> content + "// changed\n" }
        val prepared = AssetsOutputWriter.prepare(updated)
        try {
            Files.delete(prepared.getValue(root.resolve("lib/generate/fonts.g.dart")))
            assertThrows(Exception::class.java) { AssetsOutputWriter.publish(updated, prepared) }
            contents.forEach { (path, content) -> assertEquals(content, Files.readString(path)) }
        } finally { AssetsOutputWriter.cleanup(prepared) }
    }

    @Test fun generatedPartsAreExcludedFromDirectoryDeclarations() {
        file("lib/logo.png")
        write(generate())
        val contents = generate(yaml = "name: sample\nflutter:\n  assets: [lib/, lib/generate/]\n")
        val assets = contents.getValue(root.resolve("lib/generate/assets.g.dart"))
        assertTrue(assets.contains("lib/logo.png"))
        assertFalse(assets.contains("lib/r.dart")); assertFalse(assets.contains("lib/generate/fonts.g.dart"))
        assertFalse(assets.contains("lib/generate/assets.g.dart")); assertFalse(assets.contains("lib/generate/shaders.g.dart"))
    }

    @Test fun explicitPartResourceAndEntryFilenameConflictsAreRejected() {
        file("lib/generate/fonts.g.dart")
        assertThrows(IllegalArgumentException::class.java) { generate(yaml = "name: sample\nflutter:\n  assets: [lib/generate/fonts.g.dart]\n") }
        listOf("assets", "fonts", "shaders").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { generate(AssetsSyncConfig(outPath = "lib/generate/$name.g.dart")) }
        }
    }

    @Test fun outputAndStagingEventsDoNotTriggerResourceGeneration() {
        val outputs = generate().keys
        assertTrue(AssetsOutputWriter.isOutputEvent(root.resolve("lib/generate/assets.g.dart").toString(), outputs))
        assertTrue(AssetsOutputWriter.isOutputEvent(root.resolve("lib/r.dart").toString(), outputs))
        assertTrue(AssetsOutputWriter.isOutputEvent(root.resolve("lib/.fluttertools-123.tmp").toString(), outputs))
        assertFalse(AssetsOutputWriter.isOutputEvent(root.resolve("lib/logo.png").toString(), outputs))
        assertFalse(AssetsOutputWriter.isOutputEvent(root.resolve("other/.fluttertools-123.tmp").toString(), outputs))
    }
    private fun legacyPart(name: String, entry: String = "r.dart") = file("lib/$name.g.dart",
        AssetsGenerator.GENERATED_MARKER + "\n// dart format: off\npart of '$entry';\n// old output\n")

    @Test fun legacyPartsMoveToGenerateAndAreExcludedFromResources() {
        val obsolete = listOf("assets", "fonts", "shaders").map { legacyPart(it) }.toSet()
        file("lib/logo.png")
        assertEquals(obsolete, AssetsGenerator.legacyOutputPaths(root, AssetsSyncConfig()))
        val contents = generate(yaml = "name: sample\nflutter:\n  assets: [lib/]\n")
        val assets = contents.getValue(root.resolve("lib/generate/assets.g.dart"))
        assertTrue(assets.contains("lib/logo.png"))
        assertFalse(assets.contains("\"lib/assets.g.dart\""))
        write(contents, obsolete)
        assertTrue(obsolete.none { Files.exists(it) })
        assertTrue(contents.keys.all { Files.exists(it) })
    }

    @Test fun migrationPreservesUserFilesAndPartsForOtherEntries() {
        val user = file("lib/assets.g.dart", "user content")
        val other = legacyPart("fonts", "other.dart")
        assertTrue(AssetsGenerator.legacyOutputPaths(root, AssetsSyncConfig()).isEmpty())
        write(generate(), AssetsGenerator.legacyOutputPaths(root, AssetsSyncConfig()))
        assertEquals("user content", Files.readString(user))
        assertTrue(Files.readString(other).contains("part of 'other.dart'"))
    }

    @Test fun failedNewOutputReplacementKeepsLegacyFiles() {
        val obsolete = listOf("assets", "fonts", "shaders").map { legacyPart(it) }.toSet()
        val originals = obsolete.associateWith { Files.readString(it) }
        val contents = generate()
        val prepared = AssetsOutputWriter.prepare(contents)
        try {
            Files.delete(prepared.getValue(root.resolve("lib/generate/fonts.g.dart")))
            assertThrows(Exception::class.java) { AssetsOutputWriter.publish(contents, prepared, obsolete) }
            originals.forEach { (path, text) -> assertEquals(text, Files.readString(path)) }
            assertFalse(Files.exists(root.resolve("lib/generate/assets.g.dart")))
        } finally { AssetsOutputWriter.cleanup(prepared) }
    }

    @Test fun entryCanLiveInGenerateWithoutMigratingCurrentParts() {
        val config = AssetsSyncConfig(outPath = "lib/generate/r.dart")
        val contents = generate(config)
        write(contents)
        assertTrue(AssetsGenerator.legacyOutputPaths(root, config).isEmpty())
        assertTrue(contents.getValue(root.resolve("lib/generate/r.dart")).contains("part \"assets.g.dart\";"))
        assertTrue(contents.getValue(root.resolve("lib/generate/assets.g.dart")).contains("part of \"r.dart\";"))
    }

    @Test fun oldGenPartsMigrateForDefaultAndCustomEntries() {
        for (outPath in listOf("lib/r.dart", "lib/nested/resources.dart", "lib/gen/r.dart")) {
            val config = AssetsSyncConfig(outPath = outPath)
            val entry = root.resolve(outPath)
            val old = PubspecAssets.Kind.values().associateWith { kind ->
                root.resolve("lib/gen/${AssetsDartTemplate.resourceName(kind)}.g.dart")
            }
            write(old.map { (kind, path) -> path to AssetsDartTemplate.part(entry, path, config, kind, emptyList()) }.toMap() +
                (entry to AssetsDartTemplate.entry(entry, config, old.values, false)))
            assertEquals(old.values.toSet(), AssetsGenerator.legacyOutputPaths(root, config))
            val contents = generate(config, "name: sample\nflutter:\n  assets: [lib/gen/]\n")
            val assets = contents.getValue(root.resolve("lib/generate/assets.g.dart"))
            assertFalse(assets.contains("\"lib/gen/"))
            write(contents, AssetsGenerator.legacyOutputPaths(root, config))
            assertTrue(old.values.none { Files.exists(it) })
            assertTrue(Files.exists(entry))
            assertFalse(Files.readString(entry).contains("part \"gen/"))
            assertTrue(AssetsGenerator.legacyOutputPaths(root, config).isEmpty())
        }
    }

    @Test fun migrationPreservesUnrelatedGenFiles() {
        val user = file("lib/gen/assets.g.dart", "user content")
        val other = file("lib/gen/fonts.g.dart", AssetsGenerator.GENERATED_MARKER + "\npart of '../other.dart';\n")
        val asset = file("lib/gen/logo.png")
        assertTrue(AssetsGenerator.legacyOutputPaths(root, AssetsSyncConfig()).isEmpty())
        write(generate(), AssetsGenerator.legacyOutputPaths(root, AssetsSyncConfig()))
        assertEquals("user content", Files.readString(user))
        assertTrue(Files.readString(other).contains("part of '../other.dart'"))
        assertTrue(Files.exists(asset))
    }

    @Test fun failedReplacementKeepsOldGenFilesAndEntryReferences() {
        val config = AssetsSyncConfig()
        val entry = root.resolve(config.outPath)
        val old = PubspecAssets.Kind.values().associateWith { kind ->
            root.resolve("lib/gen/${AssetsDartTemplate.resourceName(kind)}.g.dart")
        }
        val originals = old.map { (kind, path) -> path to AssetsDartTemplate.part(entry, path, config, kind, emptyList()) }.toMap() +
            (entry to AssetsDartTemplate.entry(entry, config, old.values, false))
        write(originals)
        val contents = generate()
        val prepared = AssetsOutputWriter.prepare(contents)
        try {
            Files.delete(prepared.getValue(root.resolve("lib/generate/fonts.g.dart")))
            assertThrows(Exception::class.java) {
                AssetsOutputWriter.publish(contents, prepared, AssetsGenerator.legacyOutputPaths(root, config))
            }
            originals.forEach { (path, text) -> assertEquals(text, Files.readString(path)) }
            assertFalse(Files.exists(root.resolve("lib/generate/assets.g.dart")))
        } finally { AssetsOutputWriter.cleanup(prepared) }
    }

}
