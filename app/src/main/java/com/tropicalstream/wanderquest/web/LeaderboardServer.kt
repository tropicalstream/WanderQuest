package com.tropicalstream.wanderquest.web

import com.tropicalstream.wanderquest.game.Loot
import com.tropicalstream.wanderquest.game.StatsStore
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface

/**
 * Tavern Notice Board — the WanderQuest leaderboard, served straight from
 * the glasses (TapInsight CompanionServer pattern: NanoHTTPD, same network,
 * open it in any phone browser).
 *
 * Plain HTTP on port 19111: read-only data, zero secrets, and EC/RSA TLS
 * handshake cost on this CPU isn't worth it for a scoreboard (RSA TLS is
 * a proven audio-stutterer on the X3 — dossier §11).
 */
class LeaderboardServer(
    port: Int,
    private val stats: StatsStore,
    private val htmlProvider: () -> String
) : NanoHTTPD(port) {

    companion object {
        const val PORT = 19111

        /** Best-effort LAN/hotspot IP of the glasses. */
        fun deviceIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress && it.address.size == 4 }
                .map { it.hostAddress }
                .firstOrNull()
        }.getOrNull()
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/", "/index.html" -> newFixedLengthResponse(
                Response.Status.OK, "text/html; charset=utf-8", htmlProvider()
            )
            "/api/leaderboard" -> newFixedLengthResponse(
                Response.Status.OK, "application/json",
                // Game thread mutates stats concurrently; a torn read just
                // means one refresh shows slightly stale numbers.
                runCatching { buildJson() }.getOrElse { "{}" }
            )
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "lost in the wilds")
        }
    }

    private fun buildJson(): String {
        val players = JSONArray()
        // The local hero, live.
        players.put(
            JSONObject()
                .put("name", stats.playerName)
                .put("coins", stats.bankCoins + stats.satchelCoins)
                .put("level", stats.level)
                .put("paces", stats.totalSteps)
                .put("shinies", stats.shiniesFound)
                .put("duelWins", stats.duelWins)
                .put("isYou", true)
        )
        // Rivals enter the board through verified duel result codes.
        for (r in stats.rivals.sortedByDescending { it.score }.take(20)) {
            players.put(
                JSONObject()
                    .put("name", r.rivalName)
                    .put("coins", r.score)
                    .put("level", JSONObject.NULL)
                    .put("paces", JSONObject.NULL)
                    .put("shinies", JSONObject.NULL)
                    .put("duelWins", JSONObject.NULL)
                    .put("isYou", false)
                    .put("duelScore", true)
                    .put("beaten", r.won)
            )
        }
        return JSONObject()
            .put("hero", stats.playerName)
            .put("streak", stats.streakDays)
            .put("wanderTier", stats.bestWanderTier)
            .put("monstersEscaped", stats.monstersEscaped)
            .put("scares", stats.scaresSuffered)
            .put("levelProgress", Loot.levelProgress(stats.xp).toDouble())
            .put("players", players)
            .toString()
    }
}
