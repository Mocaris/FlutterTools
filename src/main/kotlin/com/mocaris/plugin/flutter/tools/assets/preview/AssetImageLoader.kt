package com.mocaris.plugin.flutter.tools.assets.preview

import com.mocaris.plugin.flutter.tools.assets.MUT_PATTERN
import com.mocaris.plugin.flutter.tools.assets.PubspecAssets
import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.event.IIOReadProgressListener
import javax.imageio.stream.FileImageInputStream

internal data class ImageRequest(val root: String, val packageName: String, val path: String) {
    val localFile: Path = Path.of(root).resolve(path).normalize()
}

internal object AssetImageLoader {
    const val MAX_BYTES = 20L * 1024 * 1024
    private const val MAX_PIXELS = 16_000_000L
    enum class Status { READY, MISSING, UNSUPPORTED, TOO_LARGE, FAILED }
    data class Info(val file: Path?, val width: Int? = null, val height: Int? = null, val bytes: Long? = null, val status: Status, val logicalFile: Path? = file)
    data class Loaded(val info: Info, val image: BufferedImage? = null)

    data class Location(val logical: Path, val file: Path?)

    fun resolve(request: ImageRequest, resolver: (PubspecAssets.Declaration) -> PubspecAssets.Resolved): Location {
        val base = resolver(PubspecAssets.Declaration(request.path, request.packageName)).file
        if (Files.isRegularFile(base)) return Location(base, base)
        if (!GeneratedAssetReferences.isImage(request.path)) return Location(base, null)
        if (!Files.isDirectory(base.parent)) return Location(base, null)
        val variant = variantDirectories(base.parent).asSequence().map { it.resolve(base.fileName) }.firstOrNull(Files::isRegularFile)
        return Location(base, variant)
    }

    internal fun variantDirectories(parent: Path): List<Path> = Files.newDirectoryStream(parent).use { siblings ->
        siblings.asSequence().take(4096).filter {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) && it.fileName.toString().matches(MUT_PATTERN)
        }.take(64).sortedBy { it.fileName.toString().removeSuffix("x").toDoubleOrNull() ?: Double.MAX_VALUE }.toList()
    }

    fun read(file: Path, maxWidth: Int, maxHeight: Int): Loaded {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        if (!Files.isRegularFile(file)) return Loaded(Info(null, status = Status.MISSING))
        if (!GeneratedAssetReferences.isImage(file.toString())) return Loaded(Info(file, bytes = Files.size(file), status = Status.READY))
        if (file.fileName.toString().endsWith(".svg", true)) return SvgPreviewLoader.read(file, maxWidth, maxHeight)
        val bytes = Files.size(file)
        if (bytes > MAX_BYTES) return Loaded(Info(file, bytes = bytes, status = Status.TOO_LARGE))
        FileImageInputStream(file.toFile()).use { stream ->
            // Instantiate locally: a global ImageIO registration would retain the plugin classloader.
            val reader = if (file.fileName.toString().endsWith(".webp", true)) {
                val provider = WebPImageReaderSpi()
                if (!provider.canDecodeInput(stream)) return Loaded(Info(file, bytes = bytes, status = Status.UNSUPPORTED))
                provider.createReaderInstance()
            } else {
                val readers = ImageIO.getImageReaders(stream)
                if (!readers.hasNext()) return Loaded(Info(file, bytes = bytes, status = Status.UNSUPPORTED))
                readers.next()
            }
            try {
                reader.addIIOReadProgressListener(cancellation)
                reader.setInput(stream, true, true)
                val format = reader.formatName.lowercase(java.util.Locale.ROOT)
                if (format !in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")) return Loaded(Info(file, bytes = bytes, status = Status.UNSUPPORTED))
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                val info = Info(file, width, height, bytes, Status.READY)
                // These readers can allocate a full frame internally, even with subsampling.
                val budget = if (format in setOf("gif", "bmp", "webp")) 4_000_000L else MAX_PIXELS
                if (width <= 0 || height <= 0 || width > 32768 || height > 32768 || width.toLong() * height > budget) {
                    return Loaded(info.copy(status = Status.TOO_LARGE))
                }
                val factor = maxOf((width + maxWidth - 1) / maxWidth, (height + maxHeight - 1) / maxHeight, 1)
                val param = reader.defaultReadParam.apply { setSourceSubsampling(factor, factor, 0, 0) }
                val sampled = reader.read(0, param)
                if (Thread.currentThread().isInterrupted) { sampled.flush(); throw InterruptedException() }
                val ratio = minOf(maxWidth.toDouble() / width, maxHeight.toDouble() / height, 1.0)
                val target = BufferedImage(maxOf(1, (width * ratio).toInt()), maxOf(1, (height * ratio).toInt()), BufferedImage.TYPE_INT_ARGB)
                val graphics = target.createGraphics()
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    graphics.drawImage(sampled, 0, 0, target.width, target.height, null)
                } finally { graphics.dispose(); sampled.flush() }
                return Loaded(info, target)
            } finally { reader.dispose() }
        }
    }
    private val cancellation = object : IIOReadProgressListener {
        override fun imageProgress(source: ImageReader, percentageDone: Float) {
            if (Thread.currentThread().isInterrupted) source.abort()
        }
        override fun imageStarted(source: ImageReader, imageIndex: Int) { imageProgress(source, 0f) }
        override fun imageComplete(source: ImageReader) {}
        override fun sequenceStarted(source: ImageReader, minIndex: Int) {}
        override fun sequenceComplete(source: ImageReader) {}
        override fun thumbnailStarted(source: ImageReader, imageIndex: Int, thumbnailIndex: Int) {}
        override fun thumbnailProgress(source: ImageReader, percentageDone: Float) { imageProgress(source, percentageDone) }
        override fun thumbnailComplete(source: ImageReader) {}
        override fun readAborted(source: ImageReader) {}
    }

}
