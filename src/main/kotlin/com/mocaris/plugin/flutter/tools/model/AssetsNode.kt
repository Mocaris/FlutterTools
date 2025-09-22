package com.mocaris.plugin.flutter.tools.model

data class AssetsNode(
    val path: String,
    val parentPath: String,
    val name: String,
    val extension: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AssetsNode

        return path == other.path
    }

    override fun hashCode(): Int {
        return path.hashCode()
    }
}