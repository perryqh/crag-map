package com.perryhertler.cragmap.map

/**
 * Quiet status chip copy. The app never has network, so "Offline" is always
 * honest; GPS quality is inferred from fix age + reported accuracy.
 */
fun gpsStatusLabel(accuracyMeters: Float?, ageMillis: Long?): String {
    if (accuracyMeters == null && ageMillis == null) return "Offline · GPS …"
    val weakAccuracy = accuracyMeters != null && accuracyMeters > 30f
    val stale = ageMillis != null && ageMillis > 30_000L
    return if (weakAccuracy || stale) "Offline · GPS weak" else "Offline · GPS ok"
}
