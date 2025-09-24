package com.mocaris.plugin.flutter.tools.model


const val PUBSPEC_FILE_NAME = "pubspec.yaml"
const val TOOLS_FILE_NAME = "flutter_tools.yaml"

val MUT_PATTERN = Regex("^[0-9]+(.[0-9]+)?[xX]$")

const val DEFAULT_OUT_PATH = "lib/r.dart"
const val DEFAULT_OUT_CLASS = "R"

// default  exclude path or file or reg pattern
val DEFAULT_EXCLUDE = setOf<String>(".DS_Store")

data class AssetsSyncConfig(
    val syncPath: Set<String>,
    val outPath: String,
    val outClass: String,
    val watcher: Boolean = false,
    val outExtension: Boolean = false,
    val excluded: Set<String> = emptySet(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AssetsSyncConfig

        if (watcher != other.watcher) return false
        if (outExtension != other.outExtension) return false
        if (syncPath != other.syncPath) return false
        if (outPath != other.outPath) return false
        if (outClass != other.outClass) return false
        if (excluded != other.excluded) return false

        return true
    }

    override fun hashCode(): Int {
        var result = watcher.hashCode()
        result = 31 * result + outExtension.hashCode()
        result = 31 * result + syncPath.hashCode()
        result = 31 * result + outPath.hashCode()
        result = 31 * result + outClass.hashCode()
        result = 31 * result + excluded.hashCode()
        return result
    }
}