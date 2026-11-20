package com.mocaris.plugin.flutter.tools.assets.preview

import com.intellij.ui.paint.PaintUtil
import com.intellij.ui.scale.ScaleContext
import com.intellij.util.ui.ImageUtil
import com.mocaris.plugin.flutter.tools.internal.svg.parser.DocumentLimits
import com.mocaris.plugin.flutter.tools.internal.svg.parser.LoaderContext
import com.mocaris.plugin.flutter.tools.internal.svg.parser.SVGLoader
import com.mocaris.plugin.flutter.tools.internal.svg.parser.resources.ResourcePolicy
import com.mocaris.plugin.flutter.tools.internal.svg.view.ViewBox
import org.w3c.dom.Element
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.roundToInt

/** Normalize the viewport before rendering. SVG intrinsic dimensions never determine bitmap allocation. */
internal object SvgPreviewLoader {
    const val MAX_BYTES = 256 * 1024
    private val number = Regex("[-+]?(?:[0-9]*\\.[0-9]+|[0-9]+)(?:[eE][-+]?[0-9]+)?")
    private val dimension = Regex("\\s*(${number.pattern})\\s*(px|pt|pc|in|cm|mm|q)?\\s*", RegexOption.IGNORE_CASE)
    private val urls = Regex("url\\s*\\(([^)]*)\\)", RegexOption.IGNORE_CASE)
    private val separators = Regex("[\\s,]+")
    private val cssDimensions = Regex("(?i)(?:^|[;{])\\s*(?:width|height)\\s*:")
    private val effectNames = setOf("mask", "filter")
    private val displacementNames = setOf("fegaussianblur", "feoffset")
    private val blocked = setOf("image", "feimage", "pattern", "foreignobject", "script", "animate", "animatetransform", "animatemotion", "set")
    private val filterSteps = setOf("feflood", "fecolormatrix", "feoffset", "fegaussianblur", "fecomposite", "feblend")
    private val loaderContext = LoaderContext.builder()
        .externalResourcePolicy(ResourcePolicy.DENY_ALL)
        .documentLimits(DocumentLimits(64, 64, 2048))
        .build()

    fun read(file: Path, maxWidth: Int, maxHeight: Int): AssetImageLoader.Loaded {
        val bytes = Files.newInputStream(file).use { it.readNBytes(MAX_BYTES + 1) }
        fun reject(status: AssetImageLoader.Status) = AssetImageLoader.Loaded(AssetImageLoader.Info(file, bytes = bytes.size.toLong(), status = status))
        if (bytes.size > MAX_BYTES) return reject(AssetImageLoader.Status.TOO_LARGE)
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder().apply {
            setErrorHandler(object : DefaultHandler() {
                override fun error(e: SAXParseException) { throw e }
                override fun fatalError(e: SAXParseException) { throw e }
            })
        }
        val document = builder.parse(ByteArrayInputStream(bytes))
        val root = document.documentElement
        if (root.localName != "svg" || root.namespaceURI != "http://www.w3.org/2000/svg") return reject(AssetImageLoader.Status.UNSUPPORTED)
        val elements = mutableListOf<Element>()
        val references = mutableMapOf<Element, MutableSet<String>>()
        val stack = java.util.ArrayDeque<Pair<Element, Int>>()
        stack.add(root to 0)
        var tokens = 0
        while (stack.isNotEmpty()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val (element, depth) = stack.removeLast()
            if (depth > 32 || elements.size >= 512) return reject(AssetImageLoader.Status.TOO_LARGE)
            if (element.tag in blocked || element.attributes.length > 64) return reject(AssetImageLoader.Status.RESTRICTED)
            fun reference(value: String) { references.getOrPut(element) { mutableSetOf() }.add(value.trim().trim('\'', '"').removePrefix("#")) }
            if (element.tag == "style") {
                if (element.textContent.contains("@import", true)) return reject(AssetImageLoader.Status.RESTRICTED)
                for (match in urls.findAll(element.textContent)) {
                    if (!fragment(match.groupValues[1])) return reject(AssetImageLoader.Status.RESTRICTED)
                    reference(match.groupValues[1])
                }
            }
            for (index in 0 until element.attributes.length) {
                val attr = element.attributes.item(index)
                if (attr.localName == "href") {
                    if (!fragment(attr.nodeValue)) return reject(AssetImageLoader.Status.RESTRICTED)
                    reference(attr.nodeValue)
                }
                for (match in urls.findAll(attr.nodeValue)) {
                    if (!fragment(match.groupValues[1])) return reject(AssetImageLoader.Status.RESTRICTED)
                    reference(match.groupValues[1])
                }
                if (attr.localName == "d" || attr.localName == "points") {
                    tokens += number.findAll(attr.nodeValue).take(8193).count()
                    if (tokens > 8192) return reject(AssetImageLoader.Status.TOO_LARGE)
                }
            }
            elements.add(element)
            // Older IDE renderers require SVG 1.1 xlink:href for SVG 2 internal references.
            if (element.hasAttribute("href")) {
                element.setAttributeNS("http://www.w3.org/1999/xlink", "xlink:href", element.getAttribute("href"))
            }
            children(element).forEach { stack.add(it to depth + 1) }
        }
        val ids = elements.filter { it.hasAttribute("id") }.associateBy { it.getAttribute("id") }
        var expanded = 0
        val chain = mutableSetOf<Element>()
        fun validateReferences(element: Element, depth: Int, effects: Int): Boolean {
            if (++expanded > 2048 || depth > 64 || !chain.add(element)) return false
            try {
                val effectDepth = effects + if (element.tag in effectNames) 1 else 0
                if (effectDepth > 2) return false
                for (id in references[element].orEmpty()) {
                    val target = ids[id] ?: return false
                    if (!validateReferences(target, depth + 1, effectDepth)) return false
                }
                return children(element).all { validateReferences(it, depth + 1, effectDepth) }
            } finally { chain.remove(element) }
        }
        if (!validateReferences(root, 0, 0)) return reject(AssetImageLoader.Status.TOO_LARGE)
        val viewBox = root.getAttribute("viewBox").trim().split(separators).mapNotNull(String::toDoubleOrNull)
            .takeIf { it.size == 4 && it.all(Double::isFinite) && it[2] > 0 && it[3] > 0 }
        val width = pixels(root.getAttribute("width")) ?: viewBox?.get(2) ?: 300.0
        val height = pixels(root.getAttribute("height")) ?: viewBox?.get(3) ?: 150.0
        if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0 || width > 1e9 || height > 1e9) return reject(AssetImageLoader.Status.TOO_LARGE)
        val scale = minOf(maxWidth / width, maxHeight / height, 1.0)
        val targetWidth = maxOf(1, (width * scale).roundToInt())
        val targetHeight = maxOf(1, (height * scale).roundToInt())
        effectStatus(elements, targetWidth, targetHeight, viewBox ?: listOf(0.0, 0.0, width, height))?.let { return reject(it) }
        root.setAttribute("width", targetWidth.toString())
        root.setAttribute("height", targetHeight.toString())
        if (viewBox == null) root.setAttribute("viewBox", "0 0 $width $height")
        // CSS root dimensions can override the bounded viewport; leave such files to the image editor.
        if (cssDimensions.containsMatchIn(root.getAttribute("style")) || elements.any { it.tag == "style" && cssDimensions.containsMatchIn(it.textContent) }) return reject(AssetImageLoader.Status.RESTRICTED)
        val output = ByteArrayOutputStream()
        TransformerFactory.newInstance().apply {
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        }.newTransformer().transform(DOMSource(document), StreamResult(output))
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        // Use the bundled renderer's public API; the platform's SVGLoader is internal.
        val svg = SVGLoader().load(ByteArrayInputStream(output.toByteArray()), null, loaderContext)
            ?: return reject(AssetImageLoader.Status.FAILED)
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        // Cache limits count physical pixels, so screen HiDPI scaling must not enlarge this bitmap.
        val image = ImageUtil.createImage(ScaleContext.createIdentity(), targetWidth.toDouble(), targetHeight.toDouble(),
            BufferedImage.TYPE_INT_ARGB, PaintUtil.RoundingMode.ROUND)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.clipRect(0, 0, targetWidth, targetHeight)
            svg.render(null, graphics, ViewBox(targetWidth.toFloat(), targetHeight.toFloat()))
        } catch (e: Exception) {
            image.flush()
            throw e
        } finally { graphics.dispose() }
        if (Thread.currentThread().isInterrupted) { image.flush(); throw InterruptedException() }
        if (image.width > maxWidth || image.height > maxHeight) { image.flush(); return reject(AssetImageLoader.Status.TOO_LARGE) }
        return AssetImageLoader.Loaded(AssetImageLoader.Info(file, width.roundToInt(), height.roundToInt(), bytes.size.toLong(), AssetImageLoader.Status.READY), image)
    }

    private val Element.tag: String get() = localName.lowercase(java.util.Locale.ROOT)
    private fun fragment(value: String) = value.trim().trim('\'', '"').startsWith('#')
    private fun effectStatus(elements: List<Element>, width: Int, height: Int, viewBox: List<Double>): AssetImageLoader.Status? {
        val effects = elements.filter { it.tag in effectNames }
        if (effects.isEmpty()) return null
        if (effects.size > 8) return AssetImageLoader.Status.TOO_LARGE
        if (elements.any { it.tag == "style" }) return AssetImageLoader.Status.RESTRICTED
        val filters = effects.filter { it.tag == "filter" }
        val steps = filters.flatMap(::children)
        if (steps.any { it.tag !in filterSteps }) return AssetImageLoader.Status.RESTRICTED
        if (steps.size > 16) return AssetImageLoader.Status.TOO_LARGE
        // shortcut: transformed/CSS filters need device-space bounds; support them when the renderer exposes an allocation budget.
        if (filters.isNotEmpty() && (elements.any { it.hasAttribute("transform") || it.hasAttribute("style") || it.tag in setOf("style", "use", "symbol") || it.tag == "svg" && it != elements.first() } ||
            filters.any { it.getAttribute("primitiveUnits").let { units -> units.isNotEmpty() && units != "userSpaceOnUse" } })) return AssetImageLoader.Status.RESTRICTED
        val deviceScale = maxOf(width / viewBox[2], height / viewBox[3])
        var margin = 0.0
        for (step in steps) {
            if (step.tag !in displacementNames) continue
            val values = if (step.tag == "fegaussianblur") step.getAttribute("stdDeviation").trim().split(separators).filter(String::isNotEmpty)
                else listOf(step.getAttribute("dx").ifEmpty { "0" }, step.getAttribute("dy").ifEmpty { "0" })
            val numbers = values.map { it.toDoubleOrNull() ?: return AssetImageLoader.Status.RESTRICTED }
            if (numbers.size > 2 || numbers.any { !it.isFinite() || step.tag == "fegaussianblur" && it < 0 }) return AssetImageLoader.Status.RESTRICTED
            val extent = numbers.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0
            margin += if (step.tag == "fegaussianblur") 4 * extent * maxOf(1.0, deviceScale) + 2 else extent * deviceScale
        }
        // Estimate retained filter channels and scratch surfaces, including pixels outside the final viewport.
        if (!margin.isFinite() || margin > 128 || (width + 2 * margin) * (height + 2 * margin) * (steps.size + effects.size * 2 + 2) > 8_000_000) return AssetImageLoader.Status.TOO_LARGE
        return null
    }
    private fun children(element: Element): List<Element> = (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }
    private fun pixels(value: String): Double? {
        val match = dimension.matchEntire(value) ?: return null
        val number = match.groupValues[1].toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].lowercase()) { "pt" -> 96.0 / 72; "pc" -> 16.0; "in" -> 96.0; "cm" -> 96.0 / 2.54; "mm" -> 96.0 / 25.4; "q" -> 96.0 / 101.6; else -> 1.0 }
        return number * multiplier
    }
}
