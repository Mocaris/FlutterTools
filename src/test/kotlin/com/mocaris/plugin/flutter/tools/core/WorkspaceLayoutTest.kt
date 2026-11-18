package com.mocaris.plugin.flutter.tools.core

import org.junit.Assert.*
import org.junit.Test

class WorkspaceLayoutTest {
    @Test fun nativeWorkspaceGlobsOverrideLegacyMelosMembershipAndRespectIgnore() {
        val rules = WorkspaceLayout.parse("workspace: [apps/*, 'packages/**']", null, "packages: ['other/**']\nignore: ['**/example', '**/private/**']")!!
        assertTrue(rules.includes("apps/app")); assertTrue(rules.includes("packages/core"))
        assertTrue(rules.includes("packages/nested/core")); assertFalse(rules.includes("other/core"))
        assertFalse(rules.includes("packages/core/example")); assertFalse(rules.includes("packages/private"))
        assertFalse(rules.includes("packages/private/core")); assertTrue(rules.includes(""))
    }

    @Test fun legacyAndModernMelosSupportBracesAndOverrides() {
        val legacy = WorkspaceLayout.parse(null, null, "packages: ['{apps,packages}/**']\nignore: ['**/example']")!!
        assertTrue(legacy.includes("apps/demo")); assertTrue(legacy.includes("packages/ui"))
        assertFalse(legacy.includes("tools/cli")); assertFalse(legacy.includes("example"))
        val modern = WorkspaceLayout.parse("workspace: [packages/a]\nmelos: {scripts: {test: 'dart test'}}", "workspace: [packages/b]", null)!!
        assertFalse(modern.includes("packages/a")); assertTrue(modern.includes("packages/b"))
        assertNotNull(modern.melos); assertNull(WorkspaceLayout.parse("name: normal", null, null))
    }
}
