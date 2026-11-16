package com.mocaris.plugin.flutter.tools.dependencies

import com.mocaris.plugin.flutter.tools.core.requireLocalized
import com.google.gson.JsonObject
import com.mocaris.plugin.flutter.tools.dependencies.search.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object PubDevClient {
    data class Metadata(val stable: String?, val newest: String?)
    private val http = PubHttpClient()
    fun validateName(name: String) {
        requireLocalized(Regex("[a-z][a-z0-9_]*").matches(name), "error.pubName", name)
    }
    fun fetch(name: String): Metadata = info(name).metadata

    fun search(query: String, page: Int = 1): PackageSearchPage {
        require(query.isNotBlank() && page > 0)
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
        return http.get("/api/search?q=$encoded&page=$page") { json ->
            PackageSearchPage(json.getAsJsonArray("packages").map { it.asJsonObject.get("package").asString.also(::validateName) }.distinct(), json.string("next") != null)
        }
    }

    fun info(name: String): PackageInfo {
        validateName(name)
        return http.get("/api/packages/$name") { parseInfo(it, name) }
    }

    fun score(name: String): PackageScore {
        validateName(name)
        return http.get("/api/packages/$name/score") { json ->
            val publisher = json.getAsJsonArray("tags")?.map { it.asString }?.firstOrNull { it.startsWith("publisher:") }?.removePrefix("publisher:")
            PackageScore(json.int("likeCount"), json.int("grantedPoints"), json.int("maxPoints"), publisher)
        }
    }

    internal fun parseInfo(json: JsonObject, name: String): PackageInfo {
        require(json.string("name") == name)
        val versions = json.getAsJsonArray("versions").mapNotNull { item ->
            val obj = item.asJsonObject
            if (obj.get("retracted")?.asBoolean == true) return@mapNotNull null
            val value = obj.string("version") ?: return@mapNotNull null
            val parsed = PubVersion.parse(value) ?: return@mapNotNull null
            val spec = obj.getAsJsonObject("pubspec") ?: return@mapNotNull null
            val environment = spec.getAsJsonObject("environment")
            parsed to PackageRelease(value, spec.string("description").orEmpty(), environment?.string("sdk"), environment?.string("flutter"))
        }.distinctBy { it.second.version }.sortedByDescending { it.first }
        val metadata = Metadata(versions.firstOrNull { it.first.prerelease.isEmpty() }?.second?.version,
            versions.firstOrNull()?.second?.version)
        val version = metadata.stable ?: metadata.newest
        val pubspec = json.getAsJsonArray("versions").firstOrNull { it.asJsonObject.string("version") == version }
            ?.asJsonObject?.getAsJsonObject("pubspec") ?: json.getAsJsonObject("latest").getAsJsonObject("pubspec")
        val environment = pubspec.getAsJsonObject("environment")
        return PackageInfo(name, metadata, version, pubspec.string("description").orEmpty(),
            environment?.string("sdk"), environment?.string("flutter"), pubspec.string("repository"), pubspec.string("documentation"), pubspec.string("homepage"),
            versions.map { it.second })
    }

    private fun JsonObject.string(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.int(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asInt
}
