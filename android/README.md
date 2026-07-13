# Native Android Local-First App

This branch contains the local-first Kotlin/Jetpack Compose Android app. The app
imports a swing video, runs MediaPipe Pose Landmarker on device, detects phases,
calculates deterministic pose metrics, stores runs in app-private storage, and
can request an OpenAI swing assessment directly from the device when an API key
is configured.

The hosted FastAPI/Streamlit implementation is preserved on the Git branch
`hosted-api` and tag `hosted-api-baseline-2026-07-13`.

## OpenAI API Key

Users can set their own OpenAI API key and model in the app Settings screen.
For debug testing, the build reads the default OpenAI key from Gradle
properties, `android/local.properties`, or the repository `.env` file. You may
also put Android-specific values in untracked `android/local.properties`:

```properties
OPENAI_API_KEY=your_test_key
GOLF_ANALYSER_OPENAI_MODEL=gpt-5.5
```

Release builds always default to an empty API key.

## Build

```bash
cd /home/larranz/projects/golf-analyser/android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Java/Android Gradle tooling is required to build this project.
