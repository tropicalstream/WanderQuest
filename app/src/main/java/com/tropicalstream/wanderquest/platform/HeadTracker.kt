package com.tropicalstream.wanderquest.platform

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * 3-DoF head orientation for world-anchored AR markers.
 *
 * TYPE_ROTATION_VECTOR is magnetometer-fused, so azimuth is absolute
 * (magnetic north referenced). World anchoring then needs only:
 *   gazeYaw(true north) = azimuth + declination + calibration offset
 *
 * Mounting-axis uncertainty on the glasses is handled two ways:
 *  1. Two selectable coordinate remaps ("axis mode" in Settings).
 *  2. GPS-course auto-calibration: while the player walks in a straight
 *     line at speed, their gaze direction ≈ their GPS course; a slow EMA
 *     nudges a persistent yaw offset toward agreement. This silently
 *     corrects any constant mounting error — anchored items stay glued
 *     to their real-world spots.
 */
class HeadTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotMat = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    /** Smoothed absolute yaw (true north), degrees [0, 360). */
    @Volatile var yawDeg = 0f
        private set
    /** Smoothed pitch, degrees. Positive = looking up. */
    @Volatile var pitchDeg = 0f
        private set
    @Volatile var hasData = false
        private set

    /** 0 = remap (X, Z) — phone-style AR; 1 = remap (Z, MINUS_X) — rotated mount. */
    @Volatile var axisMode = 0
    /** Persistent calibration offset, degrees (saved by StatsStore). */
    @Volatile var yawOffsetDeg = 0f
    @Volatile private var declinationDeg = 0f

    /**
     * Smoothed head angular speed (yaw + pitch), deg/s. THE combat signal:
     * any head motion above the tuned threshold ducks (while the foe
     * lunges) or strikes (any other time). One number, no ambiguity.
     */
    @Volatile var angularSpeedDps = 0f
        private set

    private var rawYawDeg = 0f
    private var smoothInitialized = false
    private val yawAlpha = 0.30f
    private val pitchAlpha = 0.25f

    // angular-speed sampling state
    private var lastSampleMs = 0L
    private var prevYawForVel = 0f
    private var prevPitchForVel = 0f

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        smoothInitialized = false
    }

    fun updateDeclination(fix: GeoFix) {
        runCatching {
            declinationDeg = GeomagneticField(
                fix.lat.toFloat(), fix.lon.toFloat(), 30f, fix.timeMs
            ).declination
        }
    }

    /**
     * GPS-course auto-calibration. Call on every fix; only fixes taken at a
     * confident walking pace with a valid course are used.
     */
    fun applyCourseCalibration(fix: GeoFix) {
        if (fix.speedMps < 1.1 || fix.bearingDeg.isNaN()) return
        if (!hasData) return
        val currentGaze = (rawYawDeg + declinationDeg + yawOffsetDeg + 360f) % 360f
        val err = Geo.wrapDeg(fix.bearingDeg - currentGaze).toFloat()
        // Only trust small-to-moderate errors (the player may glance sideways
        // mid-stride; a 90° disagreement is probably that, not mounting error).
        if (kotlin.math.abs(err) > 75f) return
        yawOffsetDeg = (yawOffsetDeg + 0.06f * err + 540f) % 360f - 180f
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotMat, event.values)
        if (axisMode == 0) {
            // Device held upright, camera looking out — classic AR remap.
            SensorManager.remapCoordinateSystem(
                rotMat, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped
            )
        } else {
            SensorManager.remapCoordinateSystem(
                rotMat, SensorManager.AXIS_Z, SensorManager.AXIS_MINUS_X, remapped
            )
        }
        SensorManager.getOrientation(remapped, orientation)
        val newYaw = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
        val newPitch = (-Math.toDegrees(orientation[1].toDouble())).toFloat()

        if (!smoothInitialized) {
            rawYawDeg = newYaw
            pitchDeg = newPitch
            smoothInitialized = true
            prevYawForVel = newYaw
            prevPitchForVel = newPitch
            lastSampleMs = android.os.SystemClock.elapsedRealtime()
        } else {
            // Shortest-arc exponential smoothing for yaw.
            val delta = Geo.wrapDeg(newYaw - rawYawDeg)
            rawYawDeg = (rawYawDeg + yawAlpha * delta + 360f) % 360f
            pitchDeg += pitchAlpha * (newPitch - pitchDeg)

            // ---- angular speed + shake detection (raw, unsmoothed) ----
            val nowMs = android.os.SystemClock.elapsedRealtime()
            val dtSec = ((nowMs - lastSampleMs).coerceAtLeast(1L)) / 1000f
            lastSampleMs = nowMs
            val yawVel = Geo.wrapDeg(newYaw - prevYawForVel) / dtSec
            val pitchVel = (newPitch - prevPitchForVel) / dtSec
            prevYawForVel = newYaw
            prevPitchForVel = newPitch
            val speed = kotlin.math.abs(yawVel) + kotlin.math.abs(pitchVel)
            angularSpeedDps += 0.35f * (speed - angularSpeedDps)

        }
        yawDeg = (rawYawDeg + declinationDeg + yawOffsetDeg + 360f) % 360f
        hasData = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
