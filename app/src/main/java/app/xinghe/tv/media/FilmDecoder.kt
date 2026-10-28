package app.xinghe.tv.media

/**
 * These TVs have one video decoder between them and every moving picture: a second one opened
 * beside the wallpaper's film stops that film where it stands (Xiaomi MiTV-MFTR0, TCL MT5879 —
 * the wallpaper froze and never came back, 1.5.5).
 *
 * So the players say when they hold it, and whoever else needs a moment with it asks rather than
 * takes: the first frame of a film that has just been uploaded is worth a quarter of a second of a
 * film already playing, and nothing at all if taking it by force stops that film dead.
 */
object FilmDecoder {
    @Volatile private var busy = false

    /** Whether one of our players has the decoder open just now. */
    val held: Boolean get() = busy

    private val lock = Any()
    private val owners = HashSet<Int>()

    /** Marks the decoder as taken by [owner]; a second call for the same owner changes nothing. */
    fun hold(owner: Any) {
        synchronized(lock) {
            owners.add(System.identityHashCode(owner))
            busy = true
        }
    }

    /** The other half of [hold]: whether the decoder is free again is answered by [held]. */
    fun drop(owner: Any) {
        synchronized(lock) {
            owners.remove(System.identityHashCode(owner))
            busy = owners.isNotEmpty()
        }
    }
}
