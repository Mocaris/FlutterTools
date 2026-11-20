package com.mocaris.plugin.flutter.tools.assets.config

import com.mocaris.plugin.flutter.tools.assets.generation.AssetsGenerator
import com.mocaris.plugin.flutter.tools.core.YamlPsiTest
import org.junit.Assert.*
import org.junit.Test

class AssetsConfigEditorTest : YamlPsiTest() {
    @Test fun generationDirectoryDefaultsAndPersistsInBlockAndFlowYaml() {
        for (source in listOf("flutter_tools:\n  assets_sync:\n    watch: false # keep", "flutter_tools: {assets_sync: {watch: false}}")) {
            val previous = AssetsGenerator.parseText(source)
            assertEquals(DEFAULT_GENERATE_DIR, previous.generateDir)
            val config = previous.copy(generateDir = "lib/custom resources")
            val text = AssetsConfigEditor.update(yaml(source), config, previous)
            assertEquals(config, AssetsGenerator.parseText(text))
            if (source.contains("# keep")) assertTrue(text.contains("# keep"))
        }
        for (value in listOf("''", "true", "[]")) {
            assertThrows(IllegalArgumentException::class.java) {
                AssetsGenerator.parseText("flutter_tools: {assets_sync: {generate_dir: $value}}")
            }
        }
    }
    @Test fun savingPreservesUnrelatedYamlAndUnchangedResourceComments() {
        val file = yaml("""
            # project header
            name: demo
            dependencies:
              http: ^1.0.0 # dependency comment
            flutter_tools:
              other_tool: true
              assets_sync:
                sync_path:
                  - assets # resource comment
                out_path: lib/r.dart # output comment
                out_class: R
                excluded: ['.DS_Store']
                out_extension: false
                watch: false
        """)
        val previous = AssetsGenerator.parseText(file.text)
        val savedText = AssetsConfigEditor.update(file, previous.copy(outClass = "Resources", watcher = true), previous)
        assertTrue(savedText.contains("# project header"))
        assertTrue(savedText.contains("# dependency comment"))
        assertTrue(savedText.contains("# resource comment"))
        assertFalse(savedText.contains("sync_path:"))
        assertTrue(savedText.contains("# output comment"))
        assertTrue(savedText.contains("other_tool: true"))
        val saved = AssetsGenerator.parseText(savedText)
        assertEquals("Resources", saved.outClass); assertTrue(saved.watcher)
    }
    @Test fun emptyFlowMappingAcceptsTheCompleteConfiguration() {
        val file = yaml("flutter_tools:\n  assets_sync: {}\n")
        val config = AssetsSyncConfig("lib/r.dart", "R", excluded = setOf("*.png", "assets/private/"))
        val savedText = AssetsConfigEditor.update(file, config, null)
        assertEquals(config, AssetsGenerator.parseText(savedText))
    }

    @Test fun insertedFieldsStayInsideTheResourceMapping() {
        val file = yaml("""
            name: sample
            flutter_tools:
              assets_sync:
                sync_path: [assets] # keep
              another_tool: true
            flutter:
              uses-material-design: true
        """)
        val config = AssetsGenerator.parseText(file.text)
        val text = AssetsConfigEditor.update(file, config, config)
        assertEquals(config, AssetsGenerator.parseText(text))
        assertTrue(text.contains("another_tool: true"))
        assertTrue(text.contains("uses-material-design: true"))
        assertTrue(text.contains("# keep"))
    }
    @Test fun removingLegacyPathsPreservesTheirEmbeddedComments() {
        val file = yaml("""
            flutter_tools:
              assets_sync:
                sync_path:
                  - assets # images
                  - fonts # fonts
        """)
        val previous = AssetsGenerator.parseText(file.text)
        val config = previous.copy(outClass = "Resources")
        val text = AssetsConfigEditor.update(file, config, previous)
        assertEquals(config, AssetsGenerator.parseText(text))
        assertTrue(text.contains("# images")); assertTrue(text.contains("# fonts"))
    }

    @Test fun newConfigurationDoesNotContainGeneratorResourcePaths() {
        val text = AssetsConfigEditor.update(yaml(""), AssetsSyncConfig(), null)
        assertFalse(text.contains("sync_path")); assertEquals(AssetsSyncConfig(), AssetsGenerator.parseText(text))
    }
    @Test fun legacyFlowPathsAreRemovedWithoutCorruptingTheMapping() {
        for (value in listOf("{sync_path: [assets]}", "{sync_path: [assets], out_class: R}", "{out_class: R, sync_path: [assets]}", "{sync_path: [assets],}")) {
            val file = yaml("flutter_tools:\n  assets_sync: $value\n")
            val previous = AssetsGenerator.parseText(file.text)
            val text = AssetsConfigEditor.update(file, previous, previous)
            assertFalse(text.contains("sync_path")); assertEquals(previous, AssetsGenerator.parseText(text))
        }
    }

    @Test fun savingModuleDefaultsPersistsTheDisplayedClassName() {
        val file = yaml("flutter_tools:\n  assets_sync:\n    watch: false # keep watcher comment")
        val previous = AssetsGenerator.parseText(file.text, "FeatureLoginR")
        val updated = AssetsConfigEditor.update(file, previous.copy(watcher = true), previous)
        assertEquals("FeatureLoginR", AssetsGenerator.parseText(updated).outClass)
        assertTrue(updated.contains("# keep watcher comment"))
    }

    @Test fun newConfigurationUsesTwoSpaceBlockMappingsAndSequences() {
        val config = AssetsSyncConfig(excluded = linkedSetOf(".DS_Store", "*.tmp"))
        val text = AssetsConfigEditor.update(yaml(""), config, null)
        assertTrue(text.startsWith("flutter_tools:\n  assets_sync:\n    excluded:\n      - \".DS_Store\"\n      - \"*.tmp\"\n"))
        assertTrue(text.endsWith("\n"))
        assertFalse(text.contains("{")); assertFalse(text.contains("["))
        assertEquals(config, AssetsGenerator.parseText(text))
    }

    @Test fun newlyInsertedSectionsUseBlockYamlWithoutChangingOtherSettings() {
        for (source in listOf("name: sample\ndependencies:\n  http: any # keep dependency",
            "flutter_tools:\n  other_tool: true # keep other tool")) {
            val config = AssetsSyncConfig(excluded = setOf("*.png"))
            val text = AssetsConfigEditor.update(yaml(source), config, null)
            assertFalse(text.contains("{")); assertFalse(text.contains("["))
            assertTrue(text.contains("  assets_sync:\n    excluded:\n      - \"*.png\""))
            assertEquals(config, AssetsGenerator.parseText(text))
            assertTrue(text.contains(source.substringAfter("# ")))
        }
    }

    @Test fun changingBlockSequencesPreservesCommentsAndSiblingKeys() {
        val file = yaml("""
            flutter_tools:
              assets_sync:
                excluded:
                  - old # exclusion comment
                out_class: R # class comment
              other_tool: true
        """)
        val previous = AssetsGenerator.parseText(file.text)
        val config = previous.copy(excluded = linkedSetOf("*.tmp", "assets/private/"))
        val text = AssetsConfigEditor.update(file, config, previous)
        assertEquals(config, AssetsGenerator.parseText(text))
        assertTrue(text.contains("    excluded:\n      - \"*.tmp\"\n      - \"assets/private/\""))
        assertTrue(text.contains("# exclusion comment")); assertTrue(text.contains("# class comment"))
        assertTrue(text.contains("  other_tool: true"))
    }

    @Test fun existingInlineExclusionsInBlockMappingsBecomeBlockSequences() {
        val file = yaml("flutter_tools:\n  assets_sync:\n    excluded: ['.DS_Store'] # exclusions")
        val config = AssetsGenerator.parseText(file.text)
        val text = AssetsConfigEditor.update(file, config, config)
        assertTrue(text.contains("excluded:\n      - \".DS_Store\""))
        assertTrue(text.contains("# exclusions"))
        assertEquals(config, AssetsGenerator.parseText(text))
    }

    @Test fun scalarEscapingRoundTripsSpecialCharactersInBlockAndFlowYaml() {
        val config = AssetsSyncConfig(excluded = linkedSetOf("true", "*.png", "a: b", "it's", "a\\b", "line\nbreak", "quote\""))
        for (source in listOf("", "flutter_tools: {assets_sync: {}}")) {
            val text = AssetsConfigEditor.update(yaml(source), config, null)
            assertEquals(config, AssetsGenerator.parseText(text))
        }
    }

}
