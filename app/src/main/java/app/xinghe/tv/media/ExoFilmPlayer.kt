package app.xinghe.tv.media

import android.view.SurfaceView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * The aerial films from the network, on Media3's ExoPlayer (wallpaper/AerialStream), behind the
 * same face as the owner's own videos on [LocalFilmPlayer]: the wallpaper's logic does not know
 * which one it is holding.
 */
@UnstableApi
class ExoFilmPlayer(private val exo: ExoPlayer) : FilmPlayer {
    private var view: SurfaceView? = null

    override val positionMs: Long get() = exo.currentPosition

    override val currentId: String? get() = exo.currentMediaItem?.mediaId

    override val isPlaying: Boolean get() = exo.isPlaying

    override val isIdle: Boolean get() = exo.playbackState == Player.STATE_IDLE

    override val droppedFrames: String?
        get() = exo.videoDecoderCounters?.let {
            "${it.droppedBufferCount} of ${it.renderedOutputBufferCount + it.droppedBufferCount} frames"
        }

    override fun prepare() {
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        FilmDecoder.hold(this)
    }

    override fun play() {
        exo.playWhenReady = true
        FilmDecoder.hold(this)
    }

    override fun pause() {
        exo.pause()
    }

    /** The decoder is let go with the renderer: nothing of the film is decoding until [goOn]. */
    override fun letGo() {
        exo.pause()
        exo.stop()
        FilmDecoder.drop(this)
    }

    override fun goOn() {
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        exo.play()
        FilmDecoder.hold(this)
    }

    override fun skip(step: Int) {
        if (step < 0) exo.seekToPreviousMediaItem() else exo.seekToNextMediaItem()
    }

    override fun setSurfaceView(view: SurfaceView?) {
        val old = this.view
        this.view = view
        if (view != null) exo.setVideoSurfaceView(view) else old?.let { exo.clearVideoSurfaceView(it) }
    }

    override fun release() {
        FilmDecoder.drop(this)
        exo.release()
    }
}
