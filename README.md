# Zimdars Electric

Field app for Zimdars Electric LLC: quotes with NY sales tax handling, stock with holds and an order list,
customers, books and tools. Kotlin + Jetpack Compose + Room.

- Every push to `main` builds an APK on GitHub Actions and publishes it as a release.
- The app's **Settings → Check for updates** downloads the newest release and installs it.
- All business data stays on the phone. Nothing private lives in this repo.

Signing uses `app/debug.keystore` so each build installs over the last one and keeps the phone's data.
