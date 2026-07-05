package com.tropicalstream.wanderquest.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Stairs awareness for indoor (Hearth) play, via the barometer.
 *
 * The pressure sensor gives relative altitude; ~3 m of vertical change is
 * one storey. Floor 0 is wherever the session started. Without a barometer
 * the tracker reports floor 0 forever and the game simply ignores floors.
 */
class FloorTracker(context: Context) : SensorEventListener {

    companion object {
        const val FLOOR_HEIGHT_M = 3.0f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressure: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val hasBarometer get() = pressure != null

    @Volatile var floor = 0
        private set
    @Volatile var relAltitudeM = 0f
        private set

    private var baselineAlt = Float.NaN
    private var emaAlt = Float.NaN

    fun start() {
        pressure?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() = sensorManager.unregisterListener(this)

    /** Re-zero (e.g., when starting a Hearth session). */
    fun rebase() {
        baselineAlt = Float.NaN
        emaAlt = Float.NaN
        floor = 0
        relAltitudeM = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_PRESSURE) return
        val alt = SensorManager.getAltitude(
            SensorManager.PRESSURE_STANDARD_ATMOSPHERE, event.values[0]
        )
        if (emaAlt.isNaN()) {
            emaAlt = alt
            baselineAlt = alt
            return
        }
        // Slow smoothing: barometers are noisy and weather drifts slowly;
        // stair climbs (~3 m in ~10 s) come through clearly at this alpha.
        emaAlt += 0.08f * (alt - emaAlt)
        relAltitudeM = emaAlt - baselineAlt
        // Hysteresis: switch floors at 70% of a storey to avoid flapping.
        val rawFloor = relAltitudeM / FLOOR_HEIGHT_M
        val current = floor
        if (rawFloor > current + 0.7f) floor = current + 1
        else if (rawFloor < current - 0.7f) floor = current - 1
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
