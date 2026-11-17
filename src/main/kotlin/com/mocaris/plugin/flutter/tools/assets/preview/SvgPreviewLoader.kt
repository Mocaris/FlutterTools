package com.mocaris.plugin.flutter.tools.assets.preview

import com.intellij.util.SVGLoader
import org.w3c.dom.Element
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
    private val blocked = setOf("image", "filter", "mask", "pattern", "foreignObject", "script", "animate", "animateTransform", "animateMotion", "set")

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
        val stack = java.util.ArrayDeque<Pair<Element, Int>>()
        stack.add(root to 0)
        var tokens = 0
        while (stack.isNotEmpty()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val (element, depth) = stack.removeLast()
            if (depth > 32 || elements.size >= 512) return reject(AssetImageLoader.Status.TOO_LARGE)
            if (element.localName in blocked || element.attributes.length > 64) return reject(AssetImageLoader.Status.UNSUPPORTED)
            if (element.localName == "style" && (element.textContent.contains("@import", true) || urls.findAll(element.textContent).any { !fragment(it.groupValues[1]) })) return reject(AssetImageLoader.Status.UNSUPPORTED)
            for (index in 0 until element.attributes.length) {
                val attr = element.attributes.item(index)
                if (attr.localName == "href" && !fragment(attr.nodeValue) || urls.findAll(attr.nodeValue).any { !fragment(it.groupValues[1]) }) return reject(AssetImageLoader.Status.UNSUPPORTED)
                if (attr.localName in setOf("d", "points")) {
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
        fun validateUses(element: Element, chain: Set<Element>, depth: Int): Boolean {
            if (++expanded > 2048 || depth > 64 || element in chain) return false
            if (element.localName == "use") {
                val href = element.getAttribute("href").ifEmpty { element.getAttributeNS("http://www.w3.org/1999/xlink", "href") }
                val target = ids[href.trim().removePrefix("#")] ?: return false
                if (!validateUses(target, chain + element, depth + 1)) return false
            }
            return children(element).all { validateUses(it, chain + element, depth + 1) }
        }
        if (!validateUses(root, emptySet(), 0)) return reject(AssetImageLoader.Status.TOO_LARGE)
        val viewBox = root.getAttribute("viewBox").trim().split(Regex("[\\s,]+")).mapNotNull(String::toDoubleOrNull)
            .takeIf { it.size == 4 && it.all(Double::isFinite) && it[2] > 0 && it[3] > 0 }
        val width = pixels(root.getAttribute("width")) ?: viewBox?.get(2) ?: 300.0
        val height = pixels(root.getAttribute("height")) ?: viewBox?.get(3) ?: 150.0
        if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0 || width > 1e9 || height > 1e9) return reject(AssetImageLoader.Status.TOO_LARGE)
        val scale = minOf(maxWidth / width, maxHeight / height, 1.0)
        root.setAttribute("width", maxOf(1, (width * scale).roundToInt()).toString())
        root.setAttribute("height", maxOf(1, (height * scale).roundToInt()).toString())
        if (viewBox == null) root.setAttribute("viewBox", "0 0 $width $height")
        // CSS root dimensions can override the bounded viewport; leave such files to the image editor.
        val cssDimensions = Regex("(?i)(?:^|[;{])\\s*(?:width|height)\\s*:")
        if (cssDimensions.containsMatchIn(root.getAttribute("style")) || elements.any { it.localName == "style" && cssDimensions.containsMatchIn(it.textContent) }) return reject(AssetImageLoader.Status.UNSUPPORTED)
        val output = ByteArrayOutputStream()
        TransformerFactory.newInstance().apply {
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        }.newTransformer().transform(DOMSource(document), StreamResult(output))
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val image = SVGLoader.load(null, ByteArrayInputStream(output.toByteArray()), 1f)
        if (Thread.currentThread().isInterrupted) { image.flush(); throw InterruptedException() }
        if (image.width > maxWidth || image.height > maxHeight) { image.flush(); return reject(AssetImageLoader.Status.TOO_LARGE) }
        return AssetImageLoader.Loaded(AssetImageLoader.Info(file, width.roundToInt(), height.roundToInt(), bytes.size.toLong(), AssetImageLoader.Status.READY), image)
    }

    private fun fragment(value: String) = value.trim().trim('\'', '"').startsWith('#')
    private fun children(element: Element): List<Element> = (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }
    private fun pixels(value: String): Double? {
        val match = dimension.matchEntire(value) ?: return null
        val number = match.groupValues[1].toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].lowercase()) { "pt" -> 96.0 / 72; "pc" -> 16.0; "in" -> 96.0; "cm" -> 96.0 / 2.54; "mm" -> 96.0 / 25.4; "q" -> 96.0 / 101.6; else -> 1.0 }
        return number * multiplier
    }
}
