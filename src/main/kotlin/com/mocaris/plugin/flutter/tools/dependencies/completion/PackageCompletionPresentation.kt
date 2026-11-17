package com.mocaris.plugin.flutter.tools.dependencies.completion

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.mocaris.plugin.flutter.tools.core.FlutterToolsBundle
import com.mocaris.plugin.flutter.tools.core.FlutterToolsIcons
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** Native lookup rendering supplies theme colors, prefix highlighting and selection styling. */
internal object PackageCompletionPresentation {
    fun builder(candidate: PackageCandidate) = LookupElementBuilder.create(candidate.name)
        .withIcon(FlutterToolsIcons.toolWindow)
        .withPresentableText("${candidate.name}: ^${candidate.version}").bold()
        .withTailText("  " + age(candidate.published), true)
        .withTypeText(candidate.likes?.toString() ?: "—", FlutterToolsIcons.heart, true)

    internal fun age(published: String?, now: Instant = Instant.now(), locale: Locale = FlutterToolsBundle.locale): String {
        val date = published?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return "—"
        val seconds = Duration.between(date, now).seconds.coerceAtLeast(0)
        val unit = listOf("years" to 31_536_000L, "months" to 2_592_000L, "days" to 86_400L, "hours" to 3600L, "minutes" to 60L)
            .firstOrNull { seconds >= it.second } ?: return FlutterToolsBundle.format("completion.age.now", locale)
        return FlutterToolsBundle.format("completion.age.${unit.first}", locale, seconds / unit.second)
    }
}
