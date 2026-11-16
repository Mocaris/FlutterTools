package com.mocaris.plugin.flutter.tools.assets

const val PUBSPEC_FILE_NAME = "pubspec.yaml"
const val TOOLS_FILE_NAME = "flutter_tools.yaml"
const val DEFAULT_OUT_PATH = "lib/r.dart"
const val DEFAULT_OUT_CLASS = "R"
val DEFAULT_EXCLUDE = setOf(".DS_Store")

// Match Flutter's conventional lowercase resolution directory names, including 2.x.
val MUT_PATTERN = Regex("^[0-9]+(\\.[0-9]*)?x$")

data class AssetsSyncConfig(
    val outPath: String = DEFAULT_OUT_PATH,
    val outClass: String = DEFAULT_OUT_CLASS,
    val watcher: Boolean = false,
    val outExtension: Boolean = false,
    val excluded: Set<String> = DEFAULT_EXCLUDE,
)
