package app.xinghe.tv.dream

import android.service.dreams.DreamService
import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.xinghe.tv.MainActivity
import app.xinghe.tv.system.DiagLog
import app.xinghe.tv.system.MissingClipboard
import app.xinghe.tv.ui.theme.canvasDensity
import app.xinghe.tv.weather.Weather
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.xinghe.tv.ui.home.LiveFilm
import app.xinghe.tv.ui.home.Screensaver
import app.xinghe.tv.ui.home.ScreensaverSkips
import app.xinghe.tv.ui.home.rememberNow
import app.xinghe.tv.wallpaper.Wallpapers

/**
 * Aerial's screensaver as the TV's system screensaver, so it starts from any app, as on Apple TV,
 * rather than only on Home. TVs that use Android's standard screensaver (TCL among them) get it
 * set by tools/install.sh in place of the maker's own, which on TCL advertises a paid membership.
 * Xiaomi runs its own screensaver outside this mechanism; there Aerial's Home timer covers it.
 */
class AerialDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val skips = ScreensaverSkips()

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    // See MissingClipboard.
    override fun getSystemService(name: String): Any? =
        super.getSystemService(name) ?: if (name == CLIPBOARD_SERVICE) MissingClipboard.standIn else null

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Interactive so left / right can step through the films; every other key ends the dream
        // in dispatchKeyEvent, as a non-interactive dream would.
        isInteractive = true
        isFullscreen = true
        // Home's wallpaper film holds the decoder the dream's film needs (TCL MT5879).
        LiveFilm.onHome?.letDecoderGo()
        Wallpapers.init(this)
        Weather.init(this)
        val canvas = canvasDensity(resources.displayMetrics)
        setContentView(
            ComposeView(this).apply {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(canvas, LocalDensity.current.fontScale)) {
                        val now by rememberNow()
                        Screensaver(visible = true, apps = emptyList(), now = now, skips = skips)
                    }
                }
            },
        )
        // Compose finds its lifecycle on the window's root view.
        window.decorView.setViewTreeLifecycleOwner(this)
        window.decorView.setViewTreeSavedStateRegistryOwner(this)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (skips.onKey(event)) return true
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            MainActivity.dreamWokeAt = event.eventTime
            MainActivity.dreamWokeKey = event.keyCode
            DiagLog.i("dream woken by ${KeyEvent.keyCodeToString(event.keyCode)} at ${event.eventTime}")
            finish()
        }
        // The lift, if it comes here while the dream is still up. Not the cancelled one Android sends
        // as the dream loses focus: the real lift then still goes to Home (TCL MT5879).
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && event.keyCode == MainActivity.dreamWokeKey) MainActivity.dreamWokeKey = -1
        return true
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDreamingStopped() {
        // Since Android 11 the dream lives in an activity: a key press finishes it, the window is
        // detached first (DESTROYED), and only then is the dream stopped. Moving back to CREATED
        // from there throws and took Aerial down on every wake (TCL MT5879).
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) registry.currentState = Lifecycle.State.CREATED
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }
}
