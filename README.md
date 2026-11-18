# logcat-overlay-android

An Android library that adds a floating, draggable, and searchable logcat window on top of any activity.

## Features

- Floating window that sits over the app — drag to move, drag corner to resize
- Filter by log level (V / D / I / W / E)
- Full-text search with multi-word support
- Pause / resume live log stream
- Opacity cycling (100 → 80 → 60 → 40 %)
- Long-press any line to copy it to the clipboard
- Position, size, and opacity persist across activity transitions

## Integration

Copy `logcat-overlay-release.aar` into your module's `libs/` folder, then add to your module's `build.gradle`:

```groovy
dependencies {
    implementation files('libs/logcat-overlay-release.aar')
    // Required transitive deps (add if not already present):
    implementation 'androidx.recyclerview:recyclerview:1.4.0'
    implementation 'androidx.preference:preference-ktx:1.2.1'
}
```

## Usage

Initialise once from your `Application.onCreate()`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LogcatOverlay.init(this)
    }
}
```

Add a toggle to your preferences XML with key `logcat_overlay_enabled` (default `false`):

```xml
<SwitchPreferenceCompat
    app:key="logcat_overlay_enabled"
    app:defaultValue="false"
    app:title="Logcat Overlay" />
```

The overlay appears automatically on every resumed activity when the switch is on, and disappears when it is off.

## Permission

The overlay can only read the host app's own logs by default. To see all device logs (like `adb logcat`), grant the system permission once:

```
adb shell pm grant your.app.package android.permission.READ_LOGS
```

## Package

`com.devtechdesign.android.logcat`

| Class | Purpose |
|---|---|
| `LogcatOverlay` | Singleton manager — call `init(app)` once |
| `LogcatOverlayView` | The floating window view |
| `LogcatReader` | Streams `logcat` into an in-memory ring buffer |
| `LogLine` | Parsed log line data class |
