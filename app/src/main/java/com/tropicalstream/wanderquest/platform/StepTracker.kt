package com.tropicalstream.wanderquest.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Step counting with graceful degradation:
 *   TYPE_STEP_DETECTOR (event per step)
 *   -> TYPE_STEP_COUNTER (cumulative, baseline-corrected)
 *   -> accelerometer peak detection (the glasses definitely have an IMU)
 *
 * Steps quietly power the reward economy ("Wander Power") — the game never
 * lectures about exercise; walking simply makes the loot better.
 */
class StepTracker(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val detector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val counter: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val accel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** Steps recorded this session. */
    @Volatile var sessionSteps = 0L
        private set
    @Volatile var source = "none"
        private set

    /** delta = steps in this event (counter sensors batch!), total = session. */
    var onStep: ((delta: Long, sessionSteps: Long) -> Unit)? = null

    private var counterBaseline = -1L

    // accel fallback state
    private var emaGravity = 9.81f
    private var emaSignal = 0f
    private var lastStepMs = 0L
    private var aboveThreshold = false

    fun start() {
        // Step detector/counter need ACTIVITY_RECOGNITION on API 29+; the
        // accelerometer fallback needs no permission at all — if the user
        // denied the prompt, walking must still count.
        val stepPermission = context.checkSelfPermission(
            android.Manifest.permission.ACTIVITY_RECOGNITION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        when {
            stepPermission && detector != null -> {
                source = "step-detector"
                sensorManager.registerListener(this, detector, SensorManager.SENSOR_DELAY_UI)
            }
            stepPermission && counter != null -> {
                source = "step-counter"
                sensorManager.registerListener(this, counter, SensorManager.SENSOR_DELAY_UI)
            }
            accel != null -> {
                source = "accel-fallback"
                sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun resetSession() {
        sessionSteps = 0
        counterBaseline = -1
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> bumpSteps(1)
            Sensor.TYPE_STEP_COUNTER -> {
                val total = event.values[0].toLong()
                if (counterBaseline < 0) counterBaseline = total
                val delta = total - counterBaseline
                if (delta > 0) {
                    counterBaseline = total
                    bumpSteps(delta)
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val (x, y, z) = event.values
                val mag = sqrt(x * x + y * y + z * z)
                // Slow EMA tracks gravity; fast residual is the step signal.
                emaGravity += 0.02f * (mag - emaGravity)
                val residual = mag - emaGravity
                emaSignal += 0.35f * (residual - emaSignal)
                val now = SystemClock.uptimeMillis()
                if (!aboveThreshold && emaSignal > 1.6f) {
                    aboveThreshold = true
                    if (now - lastStepMs > 320L) {
                        lastStepMs = now
                        bumpSteps(1)
                    }
                } else if (aboveThreshold && emaSignal < 0.7f) {
                    aboveThreshold = false
                }
                if (abs(residual) > 40f) emaSignal = 0f // shock guard
            }
        }
    }

    private fun bumpSteps(n: Long) {
        sessionSteps += n
        onStep?.invoke(n, sessionSteps)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private operator fun FloatArray.component1() = this[0]
    private operator fun FloatArray.component2() = this[1]
    private operator fun FloatArray.component3() = this[2]
}
