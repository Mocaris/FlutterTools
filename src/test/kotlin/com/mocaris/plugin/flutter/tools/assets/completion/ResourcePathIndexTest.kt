package com.mocaris.plugin.flutter.tools.assets.completion

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class ResourcePathIndexTest {
    @get:Rule val folder = TemporaryFolder()
    private val root get() = folder.root.toPath()
    private fun file(path: String, text: String = "data") {
        val target = root.resolve(path); Files.createDirectories(target.parent); Files.writeString(target, text)
    }

    @Test fun collectsDirectoriesDescendantsAndAllResourceKinds() {
        listOf("assets/a.png", "assets/notes.txt", "assets/nested/hidden.png", "fonts/a.ttf", "shaders/a.frag").forEach { file(it) }
        val index = ResourcePathIndex.collect(root, """
            name: app
            flutter:
              assets: [assets/]
              fonts:
                - family: Example
                  fonts: [{asset: fonts/a.ttf}]
              shaders: [shaders/a.frag]
        """.trimIndent())
        assertEquals(listOf("assets/", "assets/a.png", "assets/nested/", "assets/nested/hidden.png", "assets/notes.txt", "fonts/a.ttf", "shaders/a.frag"), index.items.keys.toList())
        assertTrue(index.items.values.all { it.request.packageName == "app" && it.bytes == if (it.directory) null else 4L })
        assertEquals(listOf("assets/nested/", "assets/a.png", "assets/nested/hidden.png", "assets/notes.txt"), index.matches("assets/").map { it.request.path }.toList())
        assertEquals(listOf("assets/nested/hidden.png"), index.matches("assets/nested/").map { it.request.path }.toList())
    }

    @Test fun variantsUseOneLogicalPathAndPreferBaseOrLowestScale() {
        file("assets/3.0x/logo.png", "large"); file("assets/2.0x/logo.png", "small")
        val text = "name: app\nflutter:\n  assets: [assets/]"
        var item = ResourcePathIndex.collect(root, text).items.getValue("assets/logo.png")
        assertEquals(root.resolve("assets/2.0x/logo.png"), item.file)
        file("assets/logo.png", "base")
        item = ResourcePathIndex.collect(root, text).items.getValue("assets/logo.png")
        assertEquals(root.resolve("assets/logo.png"), item.file)
    }

    @Test fun missingEntriesDoNotSuppressValidFilesAndExplicitVariantsStayExplicit() {
        file("assets/2.0x/a.png"); file("assets/data.json")
        val snapshot = ResourcePathIndex.collect(root, "name: app\nflutter:\n  assets: [missing/, assets/absent.png, assets/2.0x/a.png, assets/data.json]")
        assertEquals(listOf("assets/2.0x/a.png", "assets/data.json"), snapshot.items.keys.toList())
        assertEquals(4, snapshot.watched.size)
        assertTrue(ResourcePathIndex.collect(root, "name: [").items.isEmpty())
    }

    @Test fun resolvesPackageResourcesAndSkipsSymlinks() {
        file("dep/lib/icons/a.svg")
        file(".dart_tool/package_config.json", """{"packages":[{"name":"dep","rootUri":"../dep","packageUri":"lib/"}]}""")
        val snapshot = ResourcePathIndex.collect(root, "name: app\nflutter:\n  assets: [packages/dep/icons/]")
        assertEquals(root.resolve("dep/lib/icons/a.svg"), snapshot.items.getValue("packages/dep/icons/a.svg").file)
        file("assets/a.txt")
        Files.createSymbolicLink(root.resolve("assets/link.txt"), root.resolve("assets/a.txt"))
        assertEquals(listOf("assets/", "assets/a.txt"), ResourcePathIndex.collect(root, "name: app\nflutter:\n  assets: [assets/]").items.keys.toList())
    }
}
