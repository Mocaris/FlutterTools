package com.mocaris.plugin.flutter.tools.dependencies.search

import com.google.gson.JsonParser
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.mocaris.plugin.flutter.tools.core.YamlSupport
import com.mocaris.plugin.flutter.tools.dependencies.PubConstraint
import com.mocaris.plugin.flutter.tools.dependencies.PubVersion
import java.nio.file.Files
import java.nio.file.Path

data class ProjectSdk(val name: String, val dart: String?, val flutter: String?,
                      val dartVersion: String?, val flutterVersion: String?) {
    /** The selected destination module never changes the SDK used for comparison. */
    fun mismatches(info: PackageInfo): List<String> = buildList {
        fun check(label: String, required: String?, constraint: String?, version: String?) {
            if (required == null) return
            if (constraint != null && PubConstraint.overlaps(required, constraint) == false ||
                version?.let(PubVersion::parse)?.let { PubConstraint.allows(required, it) } == false) add(label)
        }
        check("Dart", info.dartSdk, dart, dartVersion)
        check("Flutter", info.flutterSdk, flutter, flutterVersion)
    }
}

object ProjectSdkReader {
    fun read(project: Project): ProjectSdk? = ReadAction.compute<ProjectSdk?, RuntimeException> {
        val root = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return@compute null
        read(root)
    }

    internal fun read(root: VirtualFile): ProjectSdk? {
        val spec = root.findChild("pubspec.yaml") ?: return null
        val mapping = YamlSupport.load(text(spec)) as? Map<*, *> ?: error("Invalid pubspec.yaml")
        val environment = mapping["environment"] as? Map<*, *>
        val config = root.findChild(".dart_tool")?.findChild("package_config.json")
        val json = config?.let { runCatching { JsonParser.parseString(text(it)).asJsonObject }.getOrNull() }
        val flutterRoot = json?.getAsJsonArray("packages")?.firstOrNull {
            it.asJsonObject.get("name")?.asString == "flutter"
        }?.asJsonObject?.get("rootUri")?.asString?.let { uri ->
            runCatching {
                val resolved = Path.of(config!!.path).toUri().resolve(uri)
                if (resolved.scheme == "file") Path.of(resolved).normalize().parent?.parent else null
            }.getOrNull()
        }
        val sdkRoots = listOf(Path.of(root.path).resolve(".fvm/flutter_sdk"), flutterRoot).filterNotNull()
        val sdk = sdkRoots.firstNotNullOfOrNull { sdkRoot ->
            runCatching {
                val file = sdkRoot.resolve("bin/cache/flutter.version.json")
                if (Files.isRegularFile(file)) JsonParser.parseString(Files.readString(file)).asJsonObject else null
            }.getOrNull()
        }
        fun version(value: String?) = value?.substringBefore(' ')?.takeIf { PubVersion.parse(it) != null }
        return ProjectSdk(mapping["name"] as? String ?: root.name,
            environment?.get("sdk") as? String, environment?.get("flutter") as? String,
            version(sdk?.get("dartSdkVersion")?.asString) ?: version(json?.get("generatorVersion")?.asString),
            version(sdk?.get("frameworkVersion")?.asString))
    }

    private fun text(file: VirtualFile) = FileDocumentManager.getInstance().getDocument(file)?.text
        ?: String(file.contentsToByteArray(), file.charset)
}
