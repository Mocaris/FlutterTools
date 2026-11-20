package com.mocaris.plugin.flutter.tools.dependencies.versions

/** Dart pub version ordering, including numeric prerelease identifiers. */
data class PubVersion(val major: Int, val minor: Int, val patch: Int, val prerelease: List<String> = emptyList(), val build: List<String> = emptyList()) : Comparable<PubVersion> {
    override fun compareTo(other: PubVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (prerelease.isEmpty() != other.prerelease.isEmpty()) return if (prerelease.isEmpty()) 1 else -1
        compareIdentifiers(prerelease, other.prerelease).takeIf { it != 0 }?.let { return it }
        // Unlike SemVer, pub uses build suffixes to order otherwise identical versions.
        return compareIdentifiers(build, other.build)
    }
    fun sameCore(other: PubVersion) = major == other.major && minor == other.minor && patch == other.patch
    fun nextBreaking() = if (major == 0) PubVersion(0, minor + 1, 0) else PubVersion(major + 1, 0, 0)
    companion object {
        private val pattern = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?$")
        fun parse(value: String): PubVersion? {
            val match = pattern.matchEntire(value.trim()) ?: return null
            return try { PubVersion(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt(),
                match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList(),
                match.groupValues[5].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()) } catch (_: NumberFormatException) { null }
        }
        private fun compareIdentifiers(a: List<String>, b: List<String>): Int {
            for (index in 0 until minOf(a.size, b.size)) {
                val x = a[index]; val y = b[index]
                val xn = x.toBigIntegerOrNull(); val yn = y.toBigIntegerOrNull()
                val result = when { xn != null && yn != null -> xn.compareTo(yn); xn != null -> -1; yn != null -> 1; else -> x.compareTo(y) }
                if (result != 0) return result
            }
            return a.size.compareTo(b.size)
        }
    }
}

object PubConstraint {
    private val token = Regex("(>=|<=|>|<)\\s*([0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?)")
    private data class Bound(val version: PubVersion, val inclusive: Boolean)
    private data class Range(val lower: Bound?, val upper: Bound?)

    /** Whether two supported pub constraints have any versions in common. */
    fun overlaps(first: String, second: String): Boolean? {
        val a = range(first) ?: return null
        val b = range(second) ?: return null
        val lower = listOfNotNull(a.lower, b.lower).maxWithOrNull(compareBy<Bound> { it.version }.thenBy { !it.inclusive })
        val upper = listOfNotNull(a.upper, b.upper).minWithOrNull(compareBy<Bound> { it.version }.thenBy { it.inclusive })
        return lower == null || upper == null || lower.version < upper.version ||
            lower.version == upper.version && lower.inclusive && upper.inclusive
    }

    private fun range(constraint: String): Range? {
        val text = constraint.trim()
        if (text.isEmpty() || text == "any") return Range(null, null)
        if (text.startsWith('^')) {
            val version = PubVersion.parse(text.drop(1)) ?: return null
            return Range(Bound(version, true), Bound(version.nextBreaking(), false))
        }
        PubVersion.parse(text)?.let { return Range(Bound(it, true), Bound(it, true)) }
        val matches = token.findAll(text).toList()
        if (matches.isEmpty() || token.replace(text, "").isNotBlank()) return null
        val lower = mutableListOf<Bound>()
        val upper = mutableListOf<Bound>()
        for (match in matches) {
            val version = PubVersion.parse(match.groupValues[2]) ?: return null
            val operator = match.groupValues[1]
            (if (operator.startsWith('>')) lower else upper).add(Bound(version, operator.endsWith('=')))
        }
        return Range(lower.maxWithOrNull(compareBy<Bound> { it.version }.thenBy { !it.inclusive }),
            upper.minWithOrNull(compareBy<Bound> { it.version }.thenBy { it.inclusive }))
    }

    /** null means unsupported/invalid constraint; never claim that it is compatible. */
    fun allows(constraint: String, version: PubVersion): Boolean? {
        val text = constraint.trim()
        if (text.isEmpty() || text == "any") return true
        if (text.startsWith('^')) {
            val lower = PubVersion.parse(text.substring(1).trim()) ?: return null
            val upper = lower.nextBreaking()
            return version >= lower && version < upper && !(version.prerelease.isNotEmpty() && version.sameCore(upper))
        }
        PubVersion.parse(text)?.let { return version.compareTo(it) == 0 }
        val matches = token.findAll(text).toList()
        if (matches.isEmpty() || token.replace(text, "").isNotBlank()) return null
        return matches.all { match ->
            val bound = PubVersion.parse(match.groupValues[2]) ?: return null
            when (match.groupValues[1]) { ">=" -> version >= bound; ">" -> version > bound; "<=" -> version <= bound; else -> {
                    val explicitPrereleaseFloor = matches.any { lower ->
                        lower.groupValues[1] in setOf(">", ">=") && PubVersion.parse(lower.groupValues[2])?.let { it.prerelease.isNotEmpty() && it.sameCore(bound) } == true
                    }
                    version < bound && !(bound.prerelease.isEmpty() && bound.build.isEmpty() && version.prerelease.isNotEmpty() && version.sameCore(bound) && !explicitPrereleaseFloor)
                } }
        }
    }
}
