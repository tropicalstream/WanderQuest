plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tropicalstream.wanderquest"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tropicalstream.wanderquest"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Vendor AARs (optional at compile time — all vendor access is via reflection).
    // Copy RayNeoIPCSDK-*.aar (and optionally MercuryAndroidSDK-*.aar) from the
    // TapInsight repo's tapbrowser/libs/ into app/libs/ before building for GPS.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // Leaderboard web server (proven on X3 in TapInsight's CompanionServer)
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // ComponentActivity base
    implementation("androidx.activity:activity-ktx:1.9.3")
}
