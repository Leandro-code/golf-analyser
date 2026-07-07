# Native Android Stage 1

This is the Android-first Stage 1 client. It is a Kotlin/Jetpack Compose app
that talks to the existing FastAPI backend; the Python service still performs
pose detection, phase detection, metrics, replay rendering, persistence, and
OpenAI assessment requests.

Start the backend from the repository root:

```bash
.venv/bin/uvicorn api.main:app --host 0.0.0.0 --port 8000
```

For the Android emulator, the default API URL is `http://10.0.2.2:8000`. For a
physical device, change `BuildConfig.DEFAULT_API_BASE_URL` in
`app/build.gradle.kts` or add a small debug build variant that points at the
developer machine's LAN IP.

If the backend has `GOLF_ANALYSER_API_TOKEN` enabled, pass the same value to
Gradle:

```bash
./gradlew :app:assembleDebug -PGOLF_ANALYSER_API_TOKEN=secret
```

Java/Android Gradle tooling is required to build this project.
