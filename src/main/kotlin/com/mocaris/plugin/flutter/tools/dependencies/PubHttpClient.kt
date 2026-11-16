package com.mocaris.plugin.flutter.tools.dependencies

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.util.io.HttpRequests
import java.util.concurrent.CancellationException

/** All pub API calls share fallback and a short cooldown for an unavailable primary host. */
internal class PubHttpClient(
    private val read: (String) -> String = { url ->
        HttpRequests.request(url).connectTimeout(5000).readTimeout(10000)
            .userAgent("FlutterTools IntelliJ plugin").readString()
    },
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var retryPrimaryAt = 0L

    fun <T> get(path: String, decode: (JsonObject) -> T): T {
        val hosts = if (now() < retryPrimaryAt) listOf(MIRROR, PRIMARY) else listOf(PRIMARY, MIRROR)
        var failure: Exception? = null
        for (host in hosts) {
            try {
                val result = decode(JsonParser.parseString(read(host + path)).asJsonObject)
                if (host == PRIMARY) retryPrimaryAt = 0
                return result
            } catch (e: ProcessCanceledException) { throw e }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) throw CancellationException().apply { initCause(e) }
                if (host == PRIMARY) retryPrimaryAt = now() + 60_000
                failure?.let(e::addSuppressed)
                failure = e
            }
        }
        throw requireNotNull(failure)
    }

    companion object {
        const val PRIMARY = "https://pub.dev"
        const val MIRROR = "https://pub.flutter-io.cn"
    }
}
