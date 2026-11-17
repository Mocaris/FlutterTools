package com.mocaris.plugin.flutter.tools.assets.preview

import com.mocaris.plugin.flutter.tools.assets.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class GeneratedAssetReferencesTest {
    @get:Rule val folder = TemporaryFolder()
    private val marker = AssetsGenerator.GENERATED_MARKER

    @Test fun extractsActualGeneratedFieldsWithNestedNamesAndCustomClass() {
        val root = folder.root.toPath()
        for (name in listOf("logo.png", "logo.jpg", "vector.svg", "notes.txt")) {
            val file = root.resolve("assets/images/$name"); Files.createDirectories(file.parent); Files.writeString(file, "data")
        }
        val output = AssetsGenerator.generate(root.toString(), AssetsSyncConfig(outClass = "FeatureR"),
            "name: feature\nflutter:\n  assets: [assets/images/]\n")[root.resolve("lib/generate/assets.g.dart")]!!
        val references = GeneratedAssetReferences.parse(output).values
        assertEquals(listOf("logoJpg", "logoPng", "notes", "vector"), references.map { it.name })
        assertEquals(listOf("assets/images/logo.jpg", "assets/images/logo.png", "assets/images/notes.txt", "assets/images/vector.svg"), references.map { it.path })
        assertTrue(references.all { it.packageName == "feature" && output.substring(it.offset).startsWith("\"${it.path}\"") })
    }

    @Test fun formattingWhitespaceQuotesAndEscapedPathsAreSupported() {
        val source = marker + "\n" + """
            final CustomRResource logo = const CustomRResource(
              CustomRResourceMeta('module', 'assets/it\'s-\${'$'}logo.png', CustomRResourceKind.assets),
            );
        """
        val reference = GeneratedAssetReferences.parse(source).values.single()
        assertEquals("module", reference.packageName)
        assertEquals("assets/it's-${'$'}logo.png", reference.path)
        assertTrue(source.substring(reference.offset).startsWith("'assets/"))
    }

    @Test fun doesNotEvaluateInterpolationOrMarkUnmarkedFiles() {
        val field = "final RResource image = const RResource(RResourceMeta(\"app\", \"assets/a.png\", RResourceKind.assets));"
        assertTrue(GeneratedAssetReferences.parse(field).isEmpty())
        assertTrue(GeneratedAssetReferences.parse("$marker\n" + field.replace("Kind.assets", "Kind.fonts")).isEmpty())
        assertEquals("assets/a.frag", GeneratedAssetReferences.parse("$marker\n" + field.replace("a.png", "a.frag")).values.single().path)
        assertTrue(GeneratedAssetReferences.parse("$marker\n" + field.replace("a.png", "${'$'}name.png")).isEmpty())
    }

    @Test fun malformedLongStringsDoNotCauseRecursiveRegexBacktracking() {
        val field = "final RResource image = const RResource(RResourceMeta(\"app\", \""
        val escaped = "\\\"".repeat(100_000)
        assertTrue(GeneratedAssetReferences.parse("$marker\n$field$escaped").isEmpty())
    }

    @Test fun parsingIsBoundedAndHandlesThousandsOfFields() {
        val fields = (1..3000).joinToString("\n") { "final RResource image$it = const RResource(RResourceMeta(\"app\", \"assets/$it.png\", RResourceKind.assets));" }
        assertEquals(3000, GeneratedAssetReferences.parse("$marker\n$fields").size)
        assertTrue(GeneratedAssetReferences.parse("$marker\n" + " ".repeat(GeneratedAssetReferences.MAX_FILE_CHARS)).isEmpty())
    }
}
