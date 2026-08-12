# Native Android Local-First App

This branch contains the local-first Kotlin/Jetpack Compose Android app. The app
imports a swing video, runs MediaPipe Pose Landmarker on device, detects phases,
calculates deterministic pose metrics, stores runs in app-private storage, and
can request an OpenAI swing assessment directly from the device when an API key
is configured.

The hosted FastAPI/Streamlit implementation is preserved on the Git branch
`hosted-api` and tag `hosted-api-baseline-2026-07-13`.

## OpenAI API Key

Users can set their own OpenAI API key in the app Settings screen.
The API key is never compiled into the app from Gradle properties or the
repository `.env` file. It must be entered in Settings on each installation.
It is stored in Android encrypted preferences and is never silently downgraded
to plaintext storage.

The app currently uses the fixed, tested `gpt-5.5` model for image-based swing
assessment. Neither the key nor model is read from `.env`, `local.properties`,
or Gradle properties.

Pose extraction, swing-phase detection, metrics, video playback, and analysis
history are all on-device. The only network request is an optional AI assessment
sent directly to `https://api.openai.com/v1/responses` after the user requests
one. Android cleartext HTTP traffic is disabled for the application.

## Build

```bash
cd /home/larranz/projects/golf-analyser/android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Java/Android Gradle tooling is required to build this project.

## Internal release signing

Release builds intentionally fail unless `android/signing.properties` exists.
Copy `signing.properties.example`, point it at a private keystore, and never
commit either file. The current local workspace has a dedicated internal-only
key for tester APKs; use a separately protected production key before any store
distribution.

## Physical-device profiling

Install the signed internal APK, enable USB debugging, connect exactly one
physical phone, then run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/measure-physical-device.ps1
```

The script rejects emulators, resets Android battery statistics, waits for one
analysis, samples total PSS, and writes the app-reported duration/peak memory
plus the device battery report to `device-profile/`.
