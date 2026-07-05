package com.tropicalstream.wanderquest

import android.app.Application

/**
 * Application entry point.
 *
 * MercurySDK.init(application) is the vendor display-path init used by
 * TapInsight. WanderQuest renders via its own dual-draw BinocularSbsLayout
 * (the Everyday/Moonlight-proven path), so the Mercury AAR is OPTIONAL —
 * if it has been copied into app/libs/ we init it reflectively as a
 * courtesy; if not, nothing happens. Never crash here either way.
 */
class WanderQuestApp : Application() {

    override fun onCreate() {
        super.onCreate()
        runCatching {
            val cls = Class.forName("com.ffalcon.mercury.android.sdk.MercurySDK")
            val init = cls.getMethod("init", Application::class.java)
            init.invoke(null, this)
        }
    }
}
