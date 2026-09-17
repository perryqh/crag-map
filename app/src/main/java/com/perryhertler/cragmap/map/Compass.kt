package com.perryhertler.cragmap.map

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-shot compass heading read (degrees, 0-360, 0 = north) for Phase 3's
 * field capture — which way the phone was facing when a pin was set, so a
 * formation's recorded direction can later help tell its faces apart.
 * Registers a rotation-vector listener, takes the first reading, and
 * unregisters immediately — same single-shot pattern as the GPS captures
 * elsewhere in edit mode, never a continuous listener. Returns null if the
 * device has no rotation sensor or no reading arrives within the timeout
 * (e.g. right after cold start) rather than guessing.
 */
suspend fun readHeadingOnce(context: Context, timeoutMillis: Long = 2000): Float? {
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
    val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return null

    return withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine<Float> { cont ->
            val rotationMatrix = FloatArray(9)
            val orientation = FloatArray(3)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    val degrees = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
                    sensorManager.unregisterListener(this)
                    if (cont.isActive) cont.resume(degrees)
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            cont.invokeOnCancellation { sensorManager.unregisterListener(listener) }
        }
    }
}
