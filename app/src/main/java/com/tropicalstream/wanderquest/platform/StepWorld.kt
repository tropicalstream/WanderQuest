package com.tropicalstream.wanderquest.platform

import android.os.Handler
import android.os.Looper

/**
 * The world, driven entirely by your FEET — no GPS, no Google, no network.
 *
 * A virtual position dead-reckons forward: every step advances it ~0.75 m
 * along whichever way your head is facing. The game's world model still
 * speaks in lat/lon, but those coordinates are purely relative and never
 * leave the device — they start at an arbitrary origin and only the
 * step-to-step deltas matter. Distance to a marked destination simply
 * falls as you walk.
 *
 * A 2 s keepalive emits the current position while you stand still so the
 * world stays "live".
 */
class StepWorld(private val head: HeadTracker) {

    companion object {
        const val STEP_LENGTH_M = 0.75
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lat = 0.0
    private var lon = 0.0
    private var callback: ((GeoFix) -> Unit)? = null
    private var running = false
    private var lastStepMs = 0L

    private val keepalive = object : Runnable {
        override fun run() {
            if (!running) return
            callback?.invoke(GeoFix(lat, lon, speedMps = 0.0, source = "steps"))
            handler.postDelayed(this, 2000L)
        }
    }

    fun start(onFix: (GeoFix) -> Unit) {
        callback = onFix
        resume()
    }

    fun resume() {
        if (running) return
        running = true
        handler.removeCallbacks(keepalive)
        handler.post(keepalive)
    }

    fun pause() {
        running = false
        handler.removeCallbacks(keepalive)
    }

    /** Each detected step pushes the player forward along their heading. */
    fun onStep(deltaSteps: Long) {
        if (!running || deltaSteps <= 0) return
        val yaw = if (head.hasData) head.yawDeg.toDouble() else 0.0
        val dist = STEP_LENGTH_M * deltaSteps
        val (nlat, nlon) = Geo.destination(lat, lon, yaw, dist)
        lat = nlat
        lon = nlon
        val now = System.currentTimeMillis()
        val speed = if (lastStepMs > 0 && now - lastStepMs < 2000)
            STEP_LENGTH_M / ((now - lastStepMs).coerceAtLeast(250L) / 1000.0) else 1.0
        lastStepMs = now
        callback?.invoke(GeoFix(lat, lon, speedMps = speed.coerceAtMost(2.5), bearingDeg = Double.NaN, source = "steps"))
    }
}
