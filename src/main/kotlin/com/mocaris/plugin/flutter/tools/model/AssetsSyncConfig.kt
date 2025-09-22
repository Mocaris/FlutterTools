package com.mocaris.plugin.flutter.tools.model


const val PUBSPEC_FILE_NAME = "pubspec.yaml"
const val TOOLS_FILE_NAME = "flutter_tools.yaml"

val MUT_PATTERN = Regex("^[0-9]+(.[0-9]+)?[xX]$")

const val DEFAULT_OUT_PATH = "lib/r.dart"
const val DEFAULT_OUT_CLASS = "R"

// default  exclude path or file or reg pattern
val DEFAULT_EXCLUDE = setOf<String>(".DS_Store")

data class AssetsSyncConfig(
    val sync_path: Set<String>,
    val out_path: String,
    val out_class: String,
    val watcher: Boolean = false,
    val excluded: Set<String> = emptySet(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AssetsSyncConfig

        if (watcher != other.watcher) return false
        if (sync_path != other.sync_path) return false
        if (out_path != other.out_path) return false
        if (out_class != other.out_class) return false
        if (excluded != other.excluded) return false

        return true
    }

    override fun hashCode(): Int {
        var result = watcher.hashCode()
        result = 31 * result + sync_path.hashCode()
        result = 31 * result + out_path.hashCode()
        result = 31 * result + out_class.hashCode()
        result = 31 * result + excluded.hashCode()
        return result
    }
}