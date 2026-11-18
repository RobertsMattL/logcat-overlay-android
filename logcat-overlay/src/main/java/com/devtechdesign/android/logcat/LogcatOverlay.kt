package com.devtechdesign.android.logcat

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.preference.PreferenceManager

/**
 * Shows [LogcatOverlayView] on top of whichever activity is in front, while the
 * "Logcat overlay" switch in Settings ([PREF_KEY]) is on.
 */
object LogcatOverlay {
    const val PREF_KEY = "logcat_overlay_enabled"

    /** Window settings shared by every activity's copy of the overlay. */
    class State {
        var x = 0
        var y = 0
        var width = 0   // 0 = not placed yet; the view picks a default
        var height = 0
        var opacity = 0.8f
        var query = ""
        var minLevel = 'V'
        var paused = false

        fun save(context: Context) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt("logcat_overlay_x", x)
                .putInt("logcat_overlay_y", y)
                .putInt("logcat_overlay_w", width)
                .putInt("logcat_overlay_h", height)
                .putFloat("logcat_overlay_opacity", opacity)
                .apply()
        }

        fun load(prefs: SharedPreferences) {
            x = prefs.getInt("logcat_overlay_x", 0)
            y = prefs.getInt("logcat_overlay_y", 0)
            width = prefs.getInt("logcat_overlay_w", 0)
            height = prefs.getInt("logcat_overlay_h", 0)
            opacity = prefs.getFloat("logcat_overlay_opacity", 0.8f)
        }
    }

    private val state = State()
    private lateinit var prefs: SharedPreferences
    private var resumed: Activity? = null
    private var view: LogcatOverlayView? = null

    // Held in a field: SharedPreferences only keeps weak references to listeners.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_KEY) applyEnabled()
    }

    private val enabled: Boolean get() = prefs.getBoolean(PREF_KEY, false)

    fun init(app: Application) {
        prefs = PreferenceManager.getDefaultSharedPreferences(app)
        state.load(prefs)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed = activity
                if (enabled) show(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumed === activity) resumed = null
                hide()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        if (enabled) LogcatReader.start()
    }

    private fun applyEnabled() {
        if (enabled) {
            LogcatReader.start()
            resumed?.let { show(it) }
        } else {
            hide()
            LogcatReader.stop()
        }
    }

    private fun show(activity: Activity) {
        LogcatReader.start() // restarts reading if logcat exited
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (view?.parent === decor) return
        hide()
        val overlay = LogcatOverlayView(activity, state) {
            // ✕ turns the overlay off, same as the switch in Settings.
            prefs.edit().putBoolean(PREF_KEY, false).apply()
        }
        decor.addView(overlay, FrameLayout.LayoutParams(1, 1))
        view = overlay
        decor.post { overlay.applyGeometry() }
    }

    private fun hide() {
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        view = null
    }
}
