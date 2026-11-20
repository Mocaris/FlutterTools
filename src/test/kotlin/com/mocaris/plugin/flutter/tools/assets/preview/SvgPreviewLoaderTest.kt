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
        assertEquals(AssetImageLoader.Status.RESTRICTED, read(external).info.status)
        val complex = "<svg xmlns=\"http://www.w3.org/2000/svg\">" + "<rect width=\"1\" height=\"1\"/>".repeat(600) + "</svg>"
        assertEquals(AssetImageLoader.Status.TOO_LARGE, read(complex).info.status)
    }
    @Test fun svgFileBudgetIsCheckedBeforeParsing() {
        assertEquals(AssetImageLoader.Status.TOO_LARGE, read(" ".repeat(SvgPreviewLoader.MAX_BYTES + 1)).info.status)
    }
    @Test fun luminanceAndAlphaMasksPreserveColorsAndTransparency() {
        for (type in listOf("luminance", "alpha")) {
            val result = read("""<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32">
              <mask id="m" style="mask-type:$type" maskUnits="userSpaceOnUse" x="0" y="0" width="32" height="32">
                <rect width="16" height="32" fill="white"/>
              </mask><rect width="32" height="32" fill="red" mask="url(#m)"/>
            </svg>""")
            assertEquals(type, AssetImageLoader.Status.READY, result.info.status)
            assertEquals(0xffff0000.toInt(), result.image!!.getRGB(8, 16))
            assertEquals(0, result.image.getRGB(24, 16).ushr(24))
        }
    }
    @Test fun shadowFiltersRenderWithinThumbnailAndPopupBounds() {
        val source = """<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64">
          <defs><filter id="shadow" x="0" y="0" width="64" height="64" filterUnits="userSpaceOnUse">
            <feGaussianBlur stdDeviation="1"/><feOffset dx="6" dy="6"/>
          </filter></defs><rect x="8" y="8" width="16" height="16" fill="red" filter="url(#shadow)"/>
        </svg>"""
        for (size in listOf(32, 960)) {
            val result = read(source, size, size)
            assertEquals(AssetImageLoader.Status.READY, result.info.status)
            val image = result.image!!
            val scale = image.width / 64.0
            assertTrue(image.getRGB((28 * scale).toInt(), (28 * scale).toInt()).ushr(24) > 0)
            assertEquals(0, image.getRGB((4 * scale).toInt(), (4 * scale).toInt()).ushr(24))
            assertTrue(image.width <= size && image.height <= size)
        }
    }
    @Test fun effectCyclesAndExcessiveFiltersAreRejectedBeforeRendering() {
        val cycle = """<svg xmlns="http://www.w3.org/2000/svg"><mask id="a"><rect mask="url(#a)" width="10" height="10"/></mask><rect mask="url(#a)"/></svg>"""
        assertEquals(AssetImageLoader.Status.TOO_LARGE, read(cycle).info.status)
        for (step in listOf("<feGaussianBlur stdDeviation=\"1000000\"/>", "<FEGAUSSIANBLUR stdDeviation=\"1000000\"/>", "<feOffset dx=\"1000000\"/>", "<feFlood/>".repeat(17))) {
            val source = """<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><filter id="f">$step</filter><rect width="32" height="32" filter="url(#f)"/></svg>"""
            assertEquals(step, AssetImageLoader.Status.TOO_LARGE, read(source).info.status)
        }
        val transformed = """<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><filter id="f"><feGaussianBlur stdDeviation="1"/></filter><rect transform="scale(1000000)" width="32" height="32" filter="url(#f)"/></svg>"""
        assertEquals(AssetImageLoader.Status.RESTRICTED, read(transformed).info.status)
    }
    @Test fun separateShapesCanReuseTheSameMaskWithoutBeingTreatedAsACycle() {
        val result = read("""<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32">
          <mask id="m"><rect width="32" height="32" fill="white"/></mask>
          <rect width="16" height="32" fill="red" mask="url(#m)"/>
          <rect x="16" width="16" height="32" fill="blue" mask="url(#m)"/>
        </svg>""")
        assertEquals(AssetImageLoader.Status.READY, result.info.status)
        assertEquals(0xffff0000.toInt(), result.image!!.getRGB(8, 16))
        assertEquals(0xff0000ff.toInt(), result.image.getRGB(24, 16))
    }
    @Test fun popupFilterSurfacesHaveABudgetSeparateFromTheFinalImage() {
        val steps = "<feFlood flood-color=\"red\"/>".repeat(10)
        val source = """<svg xmlns="http://www.w3.org/2000/svg" width="960" height="720">
          <filter id="f">$steps</filter><rect width="960" height="720" filter="url(#f)"/>
        </svg>"""
        assertEquals(AssetImageLoader.Status.READY, read(source).info.status)
        assertEquals(AssetImageLoader.Status.TOO_LARGE, read(source, 960, 720).info.status)
        for (content in listOf("<feTurbulence/>", "<feImage href=\"https://example.invalid/image.png\"/>")) {
            val restricted = source.replace(steps, content)
            assertEquals(AssetImageLoader.Status.RESTRICTED, read(restricted).info.status)
        }
    }
}
