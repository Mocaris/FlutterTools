package com.mocaris.plugin.flutter.tools.dependencies

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Only successful public pub responses are persisted; request errors never replace them. */
internal class PubLocalCache(private val directory: Path) {
    data class Entry<T>(val value: T, val time: Long) {
        fun fresh(minutes: Int, now: Long = System.currentTimeMillis()) =
            time <= now && now - time < minutes.coerceIn(1, 1440) * 60_000L
    }
    private val gson = Gson()

    fun <T> read(key: String, type: Class<T>): Entry<T>? = try {
        val file = file(key)
        if (!Files.isRegularFile(file)) null else {
            val json = JsonParser.parseString(Files.readString(file)).asJsonObject
            if (json.get("schema")?.asInt != 1) null else {
                val time = json.get("time").asLong
                val value = gson.fromJson(json.get("value"), type)
                if (time <= 0 || time > System.currentTimeMillis() || value == null) null else Entry(value, time)
            }
        }
    } catch (error: Exception) {
        log.debug("Ignoring unreadable pub cache entry", error)
        null
    }

    fun write(key: String, value: Any, time: Long) {
        var temporary: Path? = null
        try {
            Files.createDirectories(directory)
            val json = JsonObject().apply {
                addProperty("schema", 1)
                addProperty("time", time)
                add("value", gson.toJsonTree(value))
            }
            temporary = Files.createTempFile(directory, "pub-", ".tmp")
            Files.writeString(temporary, gson.toJson(json))
            try { Files.move(temporary, file(key), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, file(key), StandardCopyOption.REPLACE_EXISTING) }
        } catch (error: Exception) {
            log.warn("Could not persist pub cache; keeping the in-memory response", error)
        } finally { temporary?.let { runCatching { Files.deleteIfExists(it) } } }
    }

    private fun file(key: String): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return directory.resolve("$digest.json")
    }

    companion object {
        private val log = Logger.getInstance(PubLocalCache::class.java)
        fun forProject(project: Project) = PubLocalCache(Path.of(PathManager.getSystemPath(), "flutter-tools", "pub-cache", project.locationHash))
    }
}
