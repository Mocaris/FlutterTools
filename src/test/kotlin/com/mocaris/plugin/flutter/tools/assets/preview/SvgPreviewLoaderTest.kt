package com.mocaris.plugin.flutter.tools.assets.preview

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class SvgPreviewLoaderTest {
    @get:Rule val folder = TemporaryFolder()
    private fun read(source: String, width: Int = 32, height: Int = 32): AssetImageLoader.Loaded {
        val file = folder.root.toPath().resolve("preview.svg")
        Files.writeString(file, source)
        return AssetImageLoader.read(file, width, height)
    }
    @Test fun svgViewBoxRendersAtThumbnailAndPopupBudgetsWithTransparency() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 4000 2000"><rect width="4000" height="1000" fill="#ff0000"/></svg>"""
        val thumbnail = read(svg)
        assertEquals(4000, thumbnail.info.width); assertEquals(2000, thumbnail.info.height)
        assertEquals(32, thumbnail.image!!.width); assertEquals(16, thumbnail.image.height)
        assertEquals(0xffff0000.toInt(), thumbnail.image.getRGB(16, 2))
        assertEquals(0, thumbnail.image.getRGB(16, 14).ushr(24))
        val popup = read(svg, 960, 720)
        assertEquals(960, popup.image!!.width); assertEquals(480, popup.image.height)
    }
    @Test fun hugeIntrinsicDimensionsDoNotAllocateAHugeBitmap() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="1000000000" height="1000000000" viewBox="0 0 10 10"><circle cx="5" cy="5" r="4" fill="blue"/></svg>"""
        val result = read(svg)
        assertEquals(AssetImageLoader.Status.READY, result.info.status)
        assertEquals(32, result.image!!.width); assertEquals(32, result.image.height)
    }
    @Test fun svgInternalUseGradientsAndClipPathsRenderWithinBounds() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="64" height="32">
          <defs><linearGradient id="g"><stop stop-color="red"/><stop offset="1" stop-color="blue"/></linearGradient>
          <clipPath id="clip"><rect width="64" height="32"/></clipPath><rect id="shape" width="64" height="32" fill="url(#g)"/></defs>
          <use href="#shape" clip-path="url(#clip)"/>
        </svg>"""
        val result = read(svg)
        assertEquals(AssetImageLoader.Status.READY, result.info.status)
        assertEquals(32, result.image!!.width)
        assertTrue(result.image.getRGB(10, 5).ushr(24) > 0)
    }
    @Test fun gradientsAndClippingKeepTheirRenderedColorsAndTransparency() {
        val result = read("""<svg xmlns="http://www.w3.org/2000/svg" width="64" height="32">
          <defs><linearGradient id="g"><stop stop-color="red"/><stop offset="1" stop-color="blue"/></linearGradient>
          <clipPath id="clip"><rect width="64" height="16"/></clipPath></defs>
          <rect width="64" height="32" fill="url(#g)" clip-path="url(#clip)"/>
        </svg>""")
        assertEquals(AssetImageLoader.Status.READY, result.info.status)
        val image = result.image!!
        val left = java.awt.Color(image.getRGB(2, 2), true)
        val right = java.awt.Color(image.getRGB(29, 2), true)
        assertEquals(255, left.alpha); assertEquals(255, right.alpha)
        assertTrue(left.red > right.red); assertTrue(left.blue < right.blue)
        assertEquals(0, image.getRGB(16, 14).ushr(24))
    }
    @Test fun cssAndTranslatedViewBoxRenderWithoutUpscalingSmallImages() {
        val result = read("""<svg xmlns="http://www.w3.org/2000/svg" width="20" height="10" viewBox="10 20 20 10">
          <style>.shape { fill: #00ff00; }</style>
          <path class="shape" d="M10 20 H30 V30 H10 Z"/>
        </svg>""")
        assertEquals(AssetImageLoader.Status.READY, result.info.status)
        assertEquals(20, result.image!!.width); assertEquals(10, result.image.height)
        assertEquals(0xff00ff00.toInt(), result.image.getRGB(10, 5))
    }
    @Test fun cyclicReferencesExternalImagesAndComplexDocumentsUseFallback() {
        val cyclic = """<svg xmlns="http://www.w3.org/2000/svg"><defs><g id="a"><use href="#a"/></g></defs><use href="#a"/></svg>"""
        assertNull(read(cyclic).image)
        val external = """<svg xmlns="http://www.w3.org/2000/svg"><image href="https://example.invalid/image.png"/></svg>"""
        assertEquals(AssetImageLoader.Status.UNSUPPORTED, read(external).info.status)
        val complex = "<svg xmlns=\"http://www.w3.org/2000/svg\">" + "<rect width=\"1\" height=\"1\"/>".repeat(600) + "</svg>"
        assertEquals(AssetImageLoader.Status.TOO_LARGE, read(complex).info.status)
    }
    @Test fun svgFileBudgetIsCheckedBeforeParsing() {
        assertEquals(AssetImageLoader.Status.TOO_LARGE, read(" ".repeat(SvgPreviewLoader.MAX_BYTES + 1)).info.status)
    }
}
