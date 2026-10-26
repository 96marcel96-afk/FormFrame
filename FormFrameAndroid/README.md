# FormFrame Android project

This Android Studio project wraps the FormFrame body-progress web app in a native Android WebView.

## Build an APK
- Open this folder in Android Studio (current stable).
- Let Gradle sync and install Android SDK Platform 35 if prompted.
- Select Build > Build Bundle(s) / APK(s) > Build APK(s).
- The debug APK is normally at `app/build/outputs/apk/debug/app-debug.apk`.

## GitHub Actions build
Upload this project to a GitHub repository. The workflow `.github/workflows/android.yml` builds a debug APK and publishes it as an artifact for download from the workflow run.

## Notes
- Camera permission is requested by Android and passed to the embedded web app.
- The app uses a local HTML asset and IndexedDB for photos; no photo upload backend is included.
- This is an unsigned debug APK unless you configure signing for a release build.
- Test camera, IndexedDB persistence and video export on your device before relying on it. Browser/device codec support determines whether video is MP4 or WebM.
