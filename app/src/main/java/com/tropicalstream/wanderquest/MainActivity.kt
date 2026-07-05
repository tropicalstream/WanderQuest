package com.tropicalstream.wanderquest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.tropicalstream.wanderquest.game.GameEngine
import com.tropicalstream.wanderquest.game.Screen
import com.tropicalstream.wanderquest.game.StatsStore
import com.tropicalstream.wanderquest.input.TrackpadGestureEngine
import com.tropicalstream.wanderquest.platform.FloorTracker
import com.tropicalstream.wanderquest.platform.HeadTracker
import com.tropicalstream.wanderquest.platform.StepWorld
import com.tropicalstream.wanderquest.platform.Sfx
import com.tropicalstream.wanderquest.platform.StepTracker
import com.tropicalstream.wanderquest.render.GameView
import com.tropicalstream.wanderquest.ui.BinocularSbsLayout
import com.tropicalstream.wanderquest.web.LeaderboardServer

class MainActivity : ComponentActivity() {

    private lateinit var stats: StatsStore
    private lateinit var sfx: Sfx
    private lateinit var head: HeadTracker
    private lateinit var steps: StepTracker
    private lateinit var floors: FloorTracker
    private lateinit var engine: GameEngine
    private lateinit var gameView: GameView
    private val gestures = TrackpadGestureEngine()

    /**
     * The world is driven entirely by your STEPS — dead reckoning, no GPS,
     * no Google, no network. Each step advances a virtual position along
     * your heading; distance to objects falls as you walk.
     */
    private lateinit var world: StepWorld

    private var server: LeaderboardServer? = null
    private var frameLoopRunning = false
    private var lastFrameMs = 0L

    /**
     * Force density so 1dp == 1px on the 640x480-per-eye logical canvas
     * (TapInsight-proven pattern).
     */
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply {
            densityDpi = DisplayMetrics.DENSITY_MEDIUM
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureImmersiveDisplay()

        stats = StatsStore(this)
        stats.load()
        sfx = Sfx(this)
        sfx.init()
        head = HeadTracker(this)
        steps = StepTracker(this)
        floors = FloorTracker(this)
        engine = GameEngine(stats, sfx, head, steps, floors)
        engine.startSession()
        world = StepWorld(head)
        world.start { fix -> runOnUiThread { engine.onFix(fix) } }
        steps.onStep = { delta, count ->
            engine.onStep(delta, count)
            world.onStep(delta)   // your steps move you through the world
        }


        gameView = GameView(this, engine)
        val root = BinocularSbsLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(gameView)
        setContentView(root)

        gestures.setScreenSize(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        gestures.onTap = { engine.onTap(SystemClock.uptimeMillis()) }
        gestures.onDoubleTap = { engine.onDoubleTap() }
        gestures.onTripleTap = { engine.onTripleTap() }
        gestures.onLongTap = { engine.onLongTap() }
        gestures.onSwipeVertical = { dir -> engine.onSwipeVertical(dir) }
        gestures.onSwipeHorizontal = { dir -> engine.onSwipeHorizontal(dir) }
        gestures.onLeftTap = { engine.onLeftArmTap() }
        gestures.debugSink = { s -> engine.inputDebug = s }

        requestNeededPermissions()
        startLeaderboard()
    }

    private fun configureImmersiveDisplay() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.decorView.setBackgroundColor(Color.BLACK)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configureImmersiveDisplay()
    }

    private fun requestNeededPermissions() {
        // Only the step sensor needs a permission; no location at all.
        val wanted = arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)
        val missing = wanted.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 7001)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // (Re)start the step sensor now that the permission arrived.
        steps.stop()
        steps.start()
    }

    private fun startLeaderboard() {
        runCatching {
            val srv = LeaderboardServer(LeaderboardServer.PORT, stats) {
                assets.open("leaderboard.html").bufferedReader().use { it.readText() }
            }
            srv.start(5000, true)
            server = srv
            val ip = LeaderboardServer.deviceIp()
            if (ip != null) engine.leaderboardUrl = "http://$ip:${LeaderboardServer.PORT}"
        }
    }

    // ------------------------------------------------------------------
    //  Frame loop — ~30 fps cap (vendor thermal guidance; gotcha #7)
    // ------------------------------------------------------------------

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!frameLoopRunning) return
            val now = SystemClock.uptimeMillis()
            if (now - lastFrameMs >= 33L) {
                lastFrameMs = now
                engine.update(now)
                gameView.invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onResume() {
        super.onResume()
        head.start()
        steps.start()
        floors.start()
        world.resume()
        if (engine.screen != Screen.TITLE) sfx.setAmbient(true)
        frameLoopRunning = true
        lastFrameMs = 0L
        // Remove before posting: a fast pause/resume cycle must not stack
        // a second forever-looping callback.
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onPause() {
        super.onPause()
        // Sleep button fires onPause while the glasses are still worn —
        // for a game, pausing IS correct (no jump scares on a dark screen),
        // but we must save first: RAM pressure can kill us without onDestroy.
        frameLoopRunning = false
        engine.persist()
        sfx.pauseAll()
        head.stop()
        steps.stop()
        floors.stop()
        world.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { server?.stop() }
        world.pause()
        sfx.release()
        gestures.release()
    }

    // ------------------------------------------------------------------
    //  Input plumbing (dossier §4): the right-temple CLICK is a KEY event,
    //  checked FIRST so nothing can swallow it.
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gestures.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Trackpad MotionEvents drive taps + swipes; the game has no
        // clickable views, so temple events are fully consumed here.
        if (gestures.onTouchEvent(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (gestures.onGenericMotion(ev)) return true
        return super.dispatchGenericMotionEvent(ev)
    }
}
