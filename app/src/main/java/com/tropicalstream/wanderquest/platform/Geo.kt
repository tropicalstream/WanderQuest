package com.tropicalstream.wanderquest.platform

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A GPS fix in the game's world model. */
data class GeoFix(
    val lat: Double,
    val lon: Double,
    val speedMps: Double = 0.0,
    val bearingDeg: Double = Double.NaN,
    val timeMs: Long = System.currentTimeMillis(),
    val accuracyM: Double = Double.NaN,
    val source: String = ""
)

object Geo {
    private const val R = 6371000.0 // earth radius, meters

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from point 1 to point 2, degrees [0, 360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Destination point given start, bearing (deg) and distance (m). */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val br = Math.toRadians(bearingDeg)
        val d = distM / R
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(br))
        val l2 = l1 + atan2(sin(br) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Pair(Math.toDegrees(p2), ((Math.toDegrees(l2) + 540.0) % 360.0) - 180.0)
    }

    /** Wrap an angle difference into (-180, 180]. */
    fun wrapDeg(deg: Double): Double {
        var d = deg % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    fun wrapDeg(deg: Float): Float = wrapDeg(deg.toDouble()).toFloat()
}
