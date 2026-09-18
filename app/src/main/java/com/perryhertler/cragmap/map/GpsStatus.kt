package com.perryhertler.cragmap.map

import kotlin.math.roundToInt

/**
 * Quiet status chip copy. The app never has network, so "Offline" is always
 * honest; GPS quality is inferred from fix age + reported accuracy.
 *
 * Weak/stale lines include a short reason and a nudge to wait — field survey
 * at cliff base often needs a fresher fix before capturing pins.
 */
fun gpsStatusLabel(accuracyMeters: Float?, ageMillis: Long?): String {
    if (accuracyMeters == null && ageMillis == null) return "Offline · GPS …"
    val weakAccuracy = accuracyMeters != null && accuracyMeters > 30f
    val stale = ageMillis != null && ageMillis > 30_000L
    val accuracyPart = accuracyMeters?.let { "±${it.roundToInt()}m" }
    val agePart = ageMillis?.let { age ->
        val sec = (age / 1000L).coerceAtLeast(0)
        "${sec}s old"
    }
    return when {
        weakAccuracy && stale ->
            "Offline · GPS weak ($accuracyPart, $agePart) — wait for a better fix"
        weakAccuracy ->
            "Offline · GPS weak ($accuracyPart) — wait for a better fix"
        stale ->
            "Offline · GPS stale ($agePart) — wait for a fresher fix"
        else -> "Offline · GPS ok"
    }
}
