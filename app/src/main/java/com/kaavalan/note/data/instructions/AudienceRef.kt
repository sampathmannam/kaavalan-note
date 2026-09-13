package com.kaavalan.note.data.instructions

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface AudienceRef {
    val label: String
    @Serializable @SerialName("PERSON") data class ByPerson(val personId: String, override val label: String) : AudienceRef
    @Serializable @SerialName("DESIGNATION") data class ByDesignation(val designation: String, override val label: String) : AudienceRef
    @Serializable @SerialName("STATION") data class ByStation(val station: String, override val label: String) : AudienceRef
    @Serializable @SerialName("ALL") data class ByAll(val scope: String, override val label: String) : AudienceRef
}
val AudienceRef.kind: String get() = when (this) { is AudienceRef.ByPerson -> "PERSON"; is AudienceRef.ByDesignation -> "DESIGNATION"; is AudienceRef.ByStation -> "STATION"; is AudienceRef.ByAll -> "ALL" }
val AudienceRef.target: String get() = when (this) { is AudienceRef.ByPerson -> personId; is AudienceRef.ByDesignation -> designation; is AudienceRef.ByStation -> station; is AudienceRef.ByAll -> scope }
val AudienceRef.isBroadcast: Boolean get() = when (this) { is AudienceRef.ByPerson -> false; is AudienceRef.ByDesignation -> true; is AudienceRef.ByStation -> true; is AudienceRef.ByAll -> true }
fun AudienceRef.toLabel(): String = label
fun audienceFromColumns(kind: String?, target: String?, label: String?): AudienceRef? {
    if (kind == null || target == null) return null
    val safeLabel = label ?: target
    return when (kind) {
        "PERSON" -> AudienceRef.ByPerson(personId = target, label = safeLabel)
        "DESIGNATION" -> AudienceRef.ByDesignation(designation = target, label = safeLabel)
        "STATION" -> AudienceRef.ByStation(station = target, label = safeLabel)
        "ALL" -> AudienceRef.ByAll(scope = target, label = safeLabel)
        else -> {
            // Forward-compat: a future schema migration may write an
            // `audienceKind` this build doesn't recognise. Falling back to
            // `null` demotes the row to the pre-v2.0 single-person path,
            // which is a silent UX regression. Log so the migration
            // drift is visible in logcat.
            Log.w("AudienceRef", "unknown audience kind; treating as pre-v2.0 single-person")
            null
        }
    }
}
