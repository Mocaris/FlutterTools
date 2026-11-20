package com.mocaris.plugin.flutter.tools.dependencies.pub

import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.io.HttpRequests
import com.mocaris.plugin.flutter.tools.core.localization.requireLocalized
import com.mocaris.plugin.flutter.tools.dependencies.versions.PubVersion
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

/** Pub renders version-specific Markdown; keep only inert text markup for the Swing viewer. */
internal object PackageDocuments {
    const val MAX_PAGE_BYTES = 1024 * 1024
    const val MAX_CONTENT_CHARS = 128 * 1024
    private val http = PubHttpClient({ url ->
        HttpRequests.request(url).connectTimeout(5000).readTimeout(10000).userAgent("FlutterTools IntelliJ plugin").connect { request ->
            val bytes = request.inputStream.readNBytes(MAX_PAGE_BYTES + 1)
            requireLocalized(bytes.size <= MAX_PAGE_BYTES, "docs.tooLarge")
            String(bytes, StandardCharsets.UTF_8)
        }
    })

    fun url(name: String, version: String?, tab: String): String {
        PubDevClient.validateName(name)
        require(tab in setOf("readme", "changelog"))
        requireLocalized(version == null || PubVersion.parse(version) != null, "error.updateVersion")
        val suffix = version?.let { "/versions/" + URLEncoder.encode(it, StandardCharsets.UTF_8) }.orEmpty()
        return "https://pub.dev/packages/$name$suffix" + if (tab == "changelog") "/changelog" else ""
    }

    fun fetch(name: String, version: String, tab: String): PackageDocument {
        val url = url(name, version, tab)
        return http.text(url.removePrefix(PubHttpClient.PRIMARY)) { parse(it, url, tab) }
    }

    internal fun parse(source: String, url: String, tab: String): PackageDocument {
        val body = Jsoup.parse(source, url).selectFirst(".detail-tab-$tab-content.markdown-body")
        requireLocalized(body != null, "docs.unavailable")
        val allowed = Safelist.basic().addTags("h1", "h2", "h3", "h4", "h5", "h6", "table", "thead", "tbody", "tr", "th", "td", "hr")
            .addAttributes(":all", "id").addProtocols("a", "href", "http", "https", "#")
        val clean = Jsoup.clean(body!!.html(), url, allowed)
        val html = if (clean.length <= MAX_CONTENT_CHARS) clean else "<pre>" + StringUtil.escapeXmlEntities(body.text().take(MAX_CONTENT_CHARS / 6)) + "</pre>"
        return PackageDocument(html, url)
    }
}
