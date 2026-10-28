package app.xinghe.tv.media

import android.view.SurfaceView

/**
 * A film behind Home, whichever player is behind it: the aerial films from the network on
 * Media3's ExoPlayer (wallpaper/AerialStream), the owner's own videos on [LocalFilmPlayer].
 *
 * The wallpaper's own logic — when it moves, when it stops, what it hands to the screensaver —
 * talks to this and nothing else, so a wallpaper can be played by either.
 */
interface FilmPlayer {
    /** Where the film is, in milliseconds. */
    val positionMs: Long

    /** The film that is up, by id: the aerial film's id, or "local:<id>" for an owner's video. */
    val currentId: String?

    /** True while the film is moving. */
    val isPlaying: Boolean

    /** True when the player holds no decoder, and needs [prepare] before it can move. */
    val isIdle: Boolean

    /** How many frames the decoder dropped, for the log; null when it does not say. */
    val droppedFrames: String?

    /** Gets the decoder ready for the film. */
    fun prepare()

    /** Lets the film move. */
    fun play()

    /** Holds the film on its frame. */
    fun pause()

    /**
     * Lets the decoder go, keeping the film and its place: these TVs have one 4K decoder, and a
     * screensaver of its own needs it (Xiaomi MiTV-MFTR0, TCL MT5879: a second film failed to open
     * beside the wallpaper's).
     */
    fun letGo()

    /** After [letGo], when nothing else came to need it: the film goes on. */
    fun goOn()

    /** The next film (+1) or the one before (-1) in the player's own list. */
    fun skip(step: Int)

    /** Puts the film on a surface, or takes it off (null). */
    fun setSurfaceView(view: SurfaceView?)

    /** The player is done with. */
    fun release()
}
