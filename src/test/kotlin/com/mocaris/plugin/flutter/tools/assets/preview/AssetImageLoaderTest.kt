package com.mocaris.plugin.flutter.tools.assets.preview

import com.mocaris.plugin.flutter.tools.assets.PubspecAssets
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class AssetImageLoaderTest {
    @get:Rule val folder = TemporaryFolder()
    private val root get() = folder.root.toPath()
    private fun image(path: String, width: Int = 100, height: Int = 50): Path {
        val file = root.resolve(path); Files.createDirectories(file.parent)
        val value = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = value.createGraphics(); g.color = Color.BLUE; g.fillRect(0, 0, width, height); g.dispose()
        ImageIO.write(value, path.substringAfterLast('.'), file.toFile()); value.flush()
        return file
    }

    @Test fun thumbnailAndPopupStayWithinPixelBudgetsAndRetainOriginalDimensions() {
        val file = image("wide.png", 4000, 2000)
        val thumb = AssetImageLoader.read(file, 32, 32)
        assertEquals(4000, thumb.info.width); assertEquals(2000, thumb.info.height)
        assertEquals(32, thumb.image!!.width); assertEquals(16, thumb.image.height)
        val popup = AssetImageLoader.read(file, 960, 720)
        assertEquals(960, popup.image!!.width); assertEquals(480, popup.image.height)
        assertEquals(AssetImageLoader.Status.READY, popup.info.status)
    }

    @Test fun firstFrameOfGifAndCommonRasterFormatsCanBeRead() {
        for (extension in listOf("png", "jpg", "gif", "bmp")) {
            val result = AssetImageLoader.read(image("image.$extension"), 32, 32)
            assertEquals(extension, AssetImageLoader.Status.READY, result.info.status)
            assertTrue(result.image!!.width <= 32 && result.image.height <= 32)
        }
    }
    @Test fun smallImagesKeepTheirNativeColorModelAndTransparentPixels() {
        val file = image("small.png", 16, 16)
        val native = ImageIO.read(file.toFile())
        val preview = AssetImageLoader.read(file, 32, 32).image!!
        assertEquals(native.type, preview.type)
        assertEquals(native.getRGB(8, 8), preview.getRGB(8, 8))
        val transparent = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        transparent.setRGB(8, 8, 0x80402010.toInt())
        ImageIO.write(transparent, "png", file.toFile())
        val alpha = AssetImageLoader.read(file, 32, 32).image!!
        assertEquals(0x80402010.toInt(), alpha.getRGB(8, 8))
        assertEquals(0, alpha.getRGB(0, 0).ushr(24))
        native.flush(); preview.flush(); transparent.flush(); alpha.flush()
    }

    @Test fun oversizedAndUnsupportedFilesNeverProduceBitmaps() {
        val file = root.resolve("large.png")
        java.io.RandomAccessFile(file.toFile(), "rw").use { it.setLength(AssetImageLoader.MAX_BYTES + 1) }
        assertEquals(AssetImageLoader.Status.TOO_LARGE, AssetImageLoader.read(file, 32, 32).info.status)
        Files.writeString(root.resolve("unknown.png"), "not an image")
        assertEquals(AssetImageLoader.Status.UNSUPPORTED, AssetImageLoader.read(root.resolve("unknown.png"), 32, 32).info.status)
        assertEquals(AssetImageLoader.Status.MISSING, AssetImageLoader.read(root.resolve("missing.png"), 32, 32).info.status)
    }

    @Test fun oversizedDimensionsAreRejectedFromHeadersBeforePixelDecoding() {
        val file = root.resolve("huge.png")
        java.io.DataOutputStream(Files.newOutputStream(file)).use { output ->
            output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            val header = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(header).use { data ->
                data.writeBytes("IHDR"); data.writeInt(10000); data.writeInt(4000)
                data.write(byteArrayOf(8, 2, 0, 0, 0))
            }
            val bytes = header.toByteArray()
            output.writeInt(13); output.write(bytes)
            output.writeInt(java.util.zip.CRC32().apply { update(bytes) }.value.toInt())
            output.writeInt(0); output.writeBytes("IEND"); output.writeInt(0xAE426082.toInt())
        }
        val result = AssetImageLoader.read(file, 32, 32)
        assertEquals(10000, result.info.width)
        assertEquals(AssetImageLoader.Status.TOO_LARGE, result.info.status)
        assertNull(result.image)
    }

    @Test fun interruptedDecodeDoesNotAllocateAnImage() {
        val file = image("cancelled.png")
        Thread.currentThread().interrupt()
        try {
            try { AssetImageLoader.read(file, 32, 32); fail("Cancelled decode must stop") }
            catch (_: InterruptedException) { /* Expected before file decoding starts. */ }
        } finally { Thread.interrupted() }
    }

    @Test fun logicalImagesPreferBaseThenLowestAvailableResolutionVariant() {
        val two = image("assets/2.0x/logo.png"); image("assets/10.0x/logo.png")
        val request = ImageRequest(root.toString(), "app", "assets/logo.png")
        val resolver = PubspecAssets.resolver(root)
        assertEquals(two, AssetImageLoader.resolve(request, resolver).file)
        val base = image("assets/logo.png")
        assertEquals(base, AssetImageLoader.resolve(request, resolver).file)
        Files.delete(base); Files.delete(two); Files.delete(root.resolve("assets/10.0x/logo.png"))
        assertNull(AssetImageLoader.resolve(request, resolver).file)
    }

    @Test fun packageResourcesUsePackageConfigLibraryUriAndKeepTheirLogicalDependency() {
        val file = image("dependency/lib/images/2.0x/logo.png")
        Files.createDirectories(root.resolve(".dart_tool"))
        Files.writeString(root.resolve(".dart_tool/package_config.json"), """
            {"packages":[{"name":"dependency","rootUri":"../dependency/","packageUri":"lib/"}]}
        """.trimIndent())
        val request = ImageRequest(root.toString(), "app", "packages/dependency/images/logo.png")
        val location = AssetImageLoader.resolve(request, PubspecAssets.resolver(root))
        assertEquals(file, location.file)
        assertEquals(root.resolve("dependency/lib/images/logo.png"), location.logical)
        val info = AssetImageLoader.Info(file, status = AssetImageLoader.Status.READY, logicalFile = location.logical)
        assertTrue(ImageInvalidation.affected(request, info, location.logical))
        assertTrue(ImageInvalidation.affected(request, info, file))
        assertTrue(ImageInvalidation.affected(request, info, root.resolve("dependency/lib/images/3.0x/logo.png")))
        assertTrue(ImageInvalidation.affected(request, info, root.resolve(".dart_tool/package_config.json")))
        assertFalse(ImageInvalidation.affected(request, info, root.resolve("dependency/lib/images/3.0x/other.png")))
    }

    @Test fun changedBaseImagesAndDeletedDirectoriesInvalidateWithoutRecheckingUnrelatedFiles() {
        val file = image("assets/logo.png")
        val request = ImageRequest(root.toString(), "app", "assets/logo.png")
        val info = AssetImageLoader.read(file, 32, 32).info
        assertTrue(ImageInvalidation.affected(request, info, file))
        assertTrue(ImageInvalidation.affected(request, info, file.parent))
        assertFalse(ImageInvalidation.affected(request, info, root.resolve("assets/2.0x/logo.png")))
        assertFalse(ImageInvalidation.affected(request, info, root.resolve("assets/other.png")))
    }
}
