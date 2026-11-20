package com.mocaris.plugin.flutter.tools.assets.preview

import com.mocaris.plugin.flutter.tools.assets.config.MUT_PATTERN
import com.mocaris.plugin.flutter.tools.core.DartPackageContext
import java.nio.file.Path

internal object ImageInvalidation {
    /** Compare resolved logical paths as well as concrete variants, including resources from other packages. */
    fun affected(request: ImageRequest, info: AssetImageLoader.Info, changed: Path): Boolean {
        if (DartPackageContext.metadataAffected(Path.of(request.root), changed)) return true
        val logical = info.logicalFile ?: request.localFile
        if (request.localFile.startsWith(changed) || logical.startsWith(changed) || info.file?.startsWith(changed) == true) return true
        val parent = changed.parent ?: return false
        return info.file != logical && changed.fileName == logical.fileName && parent.parent == logical.parent &&
            parent.fileName?.toString()?.matches(MUT_PATTERN) == true
    }
}
