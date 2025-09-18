package com.mocaris.plugin.flutter.tools.model

data class AssetsSyncConfig(
    val sync_path: Set<String>,
    val out_path: String,
    val out_class: String,
    val watcher: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AssetsSyncConfig

        if (watcher != other.watcher) return false
        if (sync_path != other.sync_path) return false
        if (out_path != other.out_path) return false
        if (out_class != other.out_class) return false

        return true
    }

    override fun hashCode(): Int {
        var result = watcher.hashCode()
        result = 31 * result + sync_path.hashCode()
        result = 31 * result + out_path.hashCode()
        result = 31 * result + out_class.hashCode()
        return result
    }
}