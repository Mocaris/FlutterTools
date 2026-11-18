package com.mocaris.plugin.flutter.tools.workspace

import com.mocaris.plugin.flutter.tools.core.YamlPsiTest
import com.mocaris.plugin.flutter.tools.core.YamlSupport
import org.junit.Assert.*
import org.junit.Test

class WorkspaceYamlTest : YamlPsiTest() {
    @Test fun `patches preserve unrelated content and comments`() {
        val source = "name: demo # keep\nenvironment:\n  sdk: '>=3.0.0 <4.0.0' # sdk comment\nflutter:\n  assets:\n    - assets/ # resources\n"
        val patched = WorkspaceYaml.put(environment.project, source, listOf("environment", "sdk"), ">=3.6.0 <4.0.0")
        assertTrue(patched.contains("name: demo # keep"))
        assertTrue(patched.contains("# sdk comment"))
        assertTrue(patched.contains("flutter:\n  assets:\n    - assets/ # resources"))
        assertEquals(patched, WorkspaceYaml.put(environment.project, patched, listOf("environment", "sdk"), ">=3.6.0 <4.0.0"))
    }

    @Test fun `supports flow mappings null parents and nested insertion`() {
        for (source in listOf("name: demo\nenvironment: {sdk: any,}\nmelos: {}\n", "name: demo\nenvironment: # keep\nmelos: null\n")) {
            var text = WorkspaceYaml.put(environment.project, source, listOf("environment", "sdk"), "^3.6.0")
            text = WorkspaceYaml.put(environment.project, text, listOf("melos", "scripts", "analyze"), "melos exec -- dart analyze")
            text = WorkspaceYaml.put(environment.project, text, listOf("melos", "useRootAsPackage"), true)
            val result = YamlSupport.load(text) as Map<*, *>
            assertEquals("^3.6.0", (result["environment"] as Map<*, *>)["sdk"])
            val melos = result["melos"] as Map<*, *>
            assertEquals(true, melos["useRootAsPackage"])
            assertEquals("melos exec -- dart analyze", (melos["scripts"] as Map<*, *>)["analyze"])
        }
    }

    @Test fun `adds root keys without swallowing trailing comments`() {
        val source = "name: demo\n# trailing comment\n"
        val result = WorkspaceYaml.put(environment.project, source, listOf("workspace"), listOf("packages/a"))
        assertTrue(result.contains("# trailing comment"))
        assertEquals(listOf("packages/a"), (YamlSupport.load(result) as Map<*, *>)["workspace"])
    }

    @Test fun `expands melos collections without rewriting existing block comments`() {
        for (source in listOf(
            "name: demo\nmelos: {scripts: {test: melos exec -- dart test}, ignore: [packages/private]}\n",
            "name: demo\nmelos:\n  # keep scripts\n  scripts: {test: echo test}\n  ignore: [packages/private]\n",
            "name: demo\nmelos:\n  {scripts: {test: echo test}}\n",
            "{name: demo, melos: {scripts: {test: echo test}}}\n"
        )) {
            val result = WorkspaceYaml.block(environment.project, source, "melos")
            assertEquals(YamlSupport.load(source), YamlSupport.load(result))
            assertFalse(result.contains("scripts: {"))
            assertFalse(result.contains("ignore: ["))
            assertFalse(result.contains("melos: {"))
            assertTrue(result.contains("melos:\n  "))
            assertFalse(result.contains("melos:\n    scripts"))
            if (source.contains("# keep scripts")) assertTrue(result.contains("# keep scripts"))
            assertEquals(result, WorkspaceYaml.block(environment.project, result, "melos"))
        }
    }

    @Test fun `workspace uses block lists and preserves existing block formatting`() {
        for (source in listOf("name: demo\nworkspace: [packages/a, packages/b]\n",
            "name: demo\nworkspace:\n  - packages/a # keep\n  - packages/b\n")) {
            val result = WorkspaceYaml.block(environment.project, source, "workspace")
            assertEquals(YamlSupport.load(source), YamlSupport.load(result))
            assertTrue(result.contains("workspace:\n  - packages/a"))
            assertFalse(result.contains("workspace: ["))
            if (source.contains("# keep")) assertEquals(source, result)
            assertEquals(result, WorkspaceYaml.block(environment.project, result, "workspace"))
        }
    }

    @Test fun `raises only compatible sdk floors`() {
        assertEquals(">=3.6.0 <4.0.0", WorkspaceConversion.sdkConstraint(">=3.0.0 <4.0.0"))
        assertEquals("^3.6.0", WorkspaceConversion.sdkConstraint("^3.0.0"))
        assertEquals("^3.10.0", WorkspaceConversion.sdkConstraint("^3.10.0"))
        assertThrows(IllegalArgumentException::class.java) { WorkspaceConversion.sdkConstraint(">=2.12.0 <3.0.0") }
    }
}
