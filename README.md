# FormFrame – Android APK build

The Android Studio project is in `FormFrameAndroid/`. The workflow is deliberately at repository root under `.github/workflows/`, where GitHub Actions detects it.

## Build
Open GitHub → Actions → **Build FormFrame APK** → Run workflow. After a successful run, open the run and download the `FormFrame-debug-apk` artifact. Inside it is `app-debug.apk`.

The APK is a debug build and is not signed for Play Store release. Test camera access, local storage, and video export on your device.
