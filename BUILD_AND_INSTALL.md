# Build & install — WanderQuest

Per our working agreement: Mars runs everything below; Fable only prepares it.

## One-time setup + build + install (single block)

```bash
cd ~/Projects/new-x3-app \
&& cp ~/Downloads/TapInsight-rebuild-6-11-26/gradlew . \
&& chmod +x gradlew \
&& cp ~/Downloads/TapInsight-rebuild-6-11-26/gradle/wrapper/gradle-wrapper.jar gradle/wrapper/ \
&& mkdir -p app/libs \
&& cp ~/Downloads/TapInsight-rebuild-6-11-26/tapbrowser/libs/RayNeoIPCSDK-For-Android-V0.1.0-20231128201840_9b41f025.aar app/libs/ \
&& cp ~/Downloads/TapInsight-rebuild-6-11-26/tapbrowser/libs/MercuryAndroidSDK-v0.2.2-20250717110238_48b655b3.aar app/libs/ \
&& ./gradlew :app:assembleDebug \
&& adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Notes:
- The `&&` between build and install matters — a failed build must not
  install a stale APK.
- The two `cp` AAR lines are the only vendor dependency. If you skip them
  the build STILL succeeds (vendor access is reflective); you just won't
  get phone GPS — use Settings → Hearth Mode to play indoors/simulated.
- ADB on the glasses: Settings → General → swipe to far left → trigger the
  wall-collision bounce 10x (OS ≥ 25.8.13). Windows driver issues: zadig.

## Optional: better GPS fallback

The game already layers RayNeo IPC GPS + every system location provider.
For the Google wifi-triangulation fallback tier (TapInsight unipanel
pattern), add to `~/.gradle/gradle.properties`:

```
WANDERQUEST_GOOGLE_GEO_KEY=<your Google Geolocation API key>
```

(`GOOGLE_MAPS_API_KEY` is also honored if you already have it there.)
Without a key, the IP-geolocation tier (no key needed) still seeds the
world when all else fails.

## After install

1. Launch **WanderQuest** from the RayNeo launcher.
2. Grant **Location** + **Physical activity** when prompted (one time).
3. Tap the right temple on the title screen. Outdoors with the phone
   connected (hotspot / internet share running as usual), GPS flows over
   the RayNeo IPC link automatically.
4. First play: walk straight for ~20 m so the compass auto-tunes itself
   against your GPS course (markers will settle onto their anchors).
   If the world feels mirrored/rotated even after walking, flip
   Settings → Compass Axis (A/B).
5. Leaderboard: Journal (hold the temple pad) shows the URL — open it on
   any phone on the same hotspot.

## Milestone commits (run at every milestone)

```bash
cd ~/Projects/new-x3-app \
&& rm -f .git/HEAD.lock .git/index.lock 2>/dev/null; git init -q 2>/dev/null; \
git add -A && git commit -m "WanderQuest milestone" && git push
```
(`git push` only once a remote is configured.)

## Regenerating assets (optional)

`tools/gen_sprites.py` and `tools/gen_sounds.py` (Python 3 + Pillow + numpy)
rewrite `app/src/main/assets/sprites/` and `app/src/main/res/raw/`.
