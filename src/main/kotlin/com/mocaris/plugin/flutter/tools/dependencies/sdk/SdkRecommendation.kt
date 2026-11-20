package com.mocaris.plugin.flutter.tools.dependencies.sdk

import com.mocaris.plugin.flutter.tools.dependencies.pub.PackageRelease
import com.mocaris.plugin.flutter.tools.dependencies.versions.PubConstraint
import com.mocaris.plugin.flutter.tools.dependencies.versions.PubVersion

/** This checks SDK requirements only. Pub remains responsible for solving the full dependency graph. */
object SdkRecommendation {
    enum class Status { COMPATIBLE, INCOMPATIBLE, UNKNOWN }
    data class Result(val version: String?, val status: Status)

    fun compatibility(release: PackageRelease, sdk: ProjectSdk?): Status {
        if (sdk == null || release.dartSdk == null) return Status.UNKNOWN
        fun check(required: String?, declared: String?, actual: String?): Status {
            if (required == null) return Status.COMPATIBLE
            val overlap = declared?.let { PubConstraint.overlaps(required, it) }
            val version = actual?.let(PubVersion::parse)
            val allows = version?.let { PubConstraint.allows(required, it) }
            if (overlap == false || allows == false) return Status.INCOMPATIBLE
            // A detected SDK outside the destination's environment cannot establish compatibility.
            val destinationAllows = if (declared == null || version == null) true else PubConstraint.allows(declared, version)
            if (destinationAllows == false) return Status.INCOMPATIBLE
            if (allows != true || declared != null && (overlap == null || destinationAllows == null)) return Status.UNKNOWN
            return Status.COMPATIBLE
        }
        val dart = check(release.dartSdk, sdk.dart, sdk.dartVersion)
        val flutter = check(release.flutterSdk, sdk.flutter, sdk.flutterVersion)
        return when {
            dart == Status.INCOMPATIBLE || flutter == Status.INCOMPATIBLE -> Status.INCOMPATIBLE
            dart == Status.UNKNOWN || flutter == Status.UNKNOWN -> Status.UNKNOWN
            else -> Status.COMPATIBLE
        }
    }

    fun recommend(releases: List<PackageRelease>, sdk: ProjectSdk?, prerelease: Boolean = false): Result {
        val eligible = releases.mapNotNull { release -> PubVersion.parse(release.version)?.let { it to release } }
            .filter { prerelease || it.first.prerelease.isEmpty() }.sortedByDescending { it.first }
        // If a higher release has unknown requirements, the highest compatible version is unconfirmed.
        for ((_, release) in eligible) {
            val status = compatibility(release, sdk)
            if (status == Status.UNKNOWN) return Result(null, Status.UNKNOWN)
            if (status == Status.COMPATIBLE) return Result(release.version, status)
        }
        return Result(null, if (eligible.isEmpty()) Status.UNKNOWN else Status.INCOMPATIBLE)
    }
}
