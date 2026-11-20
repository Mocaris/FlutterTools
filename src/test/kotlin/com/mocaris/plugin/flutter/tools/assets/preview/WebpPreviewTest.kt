package com.mocaris.plugin.flutter.tools.assets.preview

import com.mocaris.plugin.flutter.tools.assets.AssetRegistration
import com.mocaris.plugin.flutter.tools.assets.config.AssetsSyncConfig
import com.mocaris.plugin.flutter.tools.assets.generation.AssetsGenerator
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebpPreviewTest {
    @get:Rule val folder = TemporaryFolder()
    private fun image(name: String) = folder.root.toPath().resolve(name).also { file ->
        javaClass.getResourceAsStream("/images/$name")!!.use { Files.copy(it, file) }
    }

    @Test fun decodesLossyLosslessAlphaAndAnimationFirstFrameWithBoundedThumbnails() {
        for (name in listOf("lossy.webp", "lossless.webp", "lossy-alpha.webp", "animated.webp")) {
            val loaded = AssetImageLoader.read(image(name), 16, 16)
            assertEquals(name, AssetImageLoader.Status.READY, loaded.info.status)
            assertEquals(40, loaded.info.width); assertEquals(20, loaded.info.height)
            assertEquals(16, loaded.image!!.width); assertEquals(8, loaded.image.height)
            if (name != "lossy.webp") assertEquals(128, loaded.image.getRGB(0, 0).ushr(24))
        }
    }

    @Test fun rejectsOversizedFramesBeforeDecoding() {
        val file = image("lossy-alpha.webp")
        val bytes = Files.readAllBytes(file)
        assertEquals("VP8X", String(bytes, 12, 4))
        for ((offset, size) in listOf(24 to 4000, 27 to 2000)) {
            for (i in 0..2) bytes[offset + i] = ((size - 1) shr (i * 8)).toByte()
        }
        Files.write(file, bytes)
        val loaded = AssetImageLoader.read(file, 32, 32)
        assertEquals(AssetImageLoader.Status.TOO_LARGE, loaded.info.status)
        assertNull(loaded.image)
    }

    @Test fun webpRegistrationGenerationAndVariantResolutionShareImageRecognition() {
        val root = folder.root.toPath()
        Files.createDirectories(root.resolve("assets/2.0x"))
        Files.copy(image("lossless.webp"), root.resolve("assets/2.0x/logo.webp"))
        assertTrue(GeneratedAssetReferences.isImage("assets/LOGO.WEBP"))
        assertEquals("assets/logo.webp", AssetRegistration.logicalPath("assets/2.0x/logo.webp"))
        val outputs = AssetsGenerator.generate(root.toString(), AssetsSyncConfig(), "name: app\nflutter:\n  assets: [assets/logo.webp]\n")
        assertEquals("assets/logo.webp", GeneratedAssetReferences.parse(outputs[root.resolve("lib/generate/assets.g.dart")]!!).values.single().path)
        val request = ImageRequest(root.toString(), "app", "assets/logo.webp")
        assertEquals(root.resolve("assets/2.0x/logo.webp"), AssetImageLoader.resolve(request, com.mocaris.plugin.flutter.tools.assets.PubspecAssets.resolver(root)).file)
    }
}
