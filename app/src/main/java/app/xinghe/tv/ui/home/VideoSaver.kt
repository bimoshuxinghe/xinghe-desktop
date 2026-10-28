package app.xinghe.tv.ui.home

import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.xinghe.tv.media.LocalFilmPlayer
import app.xinghe.tv.system.DiagLog
import app.xinghe.tv.wallpaper.ScreensaverVideo
import app.xinghe.tv.wallpaper.ScreensaverVideos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext

/** A local, silent playlist, shared by Home and AerialDream. An empty or broken set falls back. */
@Composable
internal fun VideoSaver(steps: SharedFlow<Int>, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val films by produceState<List<ScreensaverVideo>?>(null) {
        value = withContext(Dispatchers.IO) { ScreensaverVideos.all(context).shuffled() }
    }
    val loaded = films ?: return
    if (loaded.isEmpty()) {
        LaunchedEffect(Unit) { onUnavailable() }
        return
    }
    LocalFilms(loaded, steps, onUnavailable)
}

/**
 * The films on a loop, played by the platform's own machinery (app/xinghe/tv/media/LocalFilmPlayer):
 * no library, no audio track and nothing read ahead, which is all a silent screensaver needs.
 */
private class FilmRun(films: List<ScreensaverVideo>, private val onUnavailable: () -> Unit) {
    val player = LocalFilmPlayer()

    var attempt by mutableIntStateOf(0)
    var playing by mutableStateOf(false)
    var aspect by mutableFloatStateOf(16f / 9f)

    init {
        player.onFirstFrame = { item ->
            playing = true
            DiagLog.i("local screensaver first frame: ${item.id}")
        }
        player.onItemChanged = { _, _ ->
            playing = false
            attempt++
        }
        player.onSize = { _, w, h ->
            if (w > 0 && h > 0) aspect = w.toFloat() / h.toFloat()
        }
        player.onError = { item, why ->
            DiagLog.i("local screensaver failed: ${item.id} ($why)")
            failed()
        }
        player.setPlaylist(films.map { LocalFilmPlayer.Item(it.id, it.file) })
    }

    fun skip(step: Int) {
        if (player.count == 0) return
        player.skip(step)
    }

    fun failed() {
        playing = false
        if (player.count <= 1) { onUnavailable(); return }
        // Only this session's queue changes. Never delete a film because one decoder rejected it.
        player.removeCurrent()
        attempt++
    }
}

@Composable
private fun LocalFilms(films: List<ScreensaverVideo>, steps: SharedFlow<Int>, onUnavailable: () -> Unit) {
    val run = remember { FilmRun(films, onUnavailable) }
    DisposableEffect(run) {
        run.player.prepare()
        run.player.play()
        onDispose { run.player.release() }
    }
    LaunchedEffect(steps) { steps.collect { run.skip(it) } }
    LaunchedEffect(run.attempt, run.playing) {
        if (!run.playing) { delay(8_000); run.failed() }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.align(Alignment.Center).aspectRatio(run.aspect),
            factory = { ctx -> SurfaceView(ctx).also { run.player.setSurfaceView(it) } },
            onRelease = { run.player.setSurfaceView(null) },
        )
    }
}
