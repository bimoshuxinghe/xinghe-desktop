package app.xinghe.tv.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import app.xinghe.tv.system.DiagLog
import java.io.File

/**
 * A player for the owner's own videos, with nothing in it but the platform's own machinery:
 * [MediaExtractor] reads the file, [MediaCodec] decodes the picture, and the frames go straight
 * to a surface. No library, no network stack, no playlist machinery, and no audio track at all.
 *
 * It replaces Media3's ExoPlayer on the local paths (the video wallpaper, the video screensaver),
 * where ExoPlayer was doing a lot of work for nothing: its default load control reads the whole
 * file ahead into memory (its target buffer is 125 MB, and a wallpaper's job is to show one frame
 * at a time), and it keeps an audio track, which on these TVs touches the audio path and the whole
 * system with it. The wallpaper's player ran through the all-apps grid, and the grid dropped
 * frames there.
 *
 * The aerial films from the network stay on ExoPlayer (wallpaper/AerialStream): they need a disk
 * cache, a playlist that follows itself and a moov-at-the-tail prefetch, and on TVs the platform's
 * own MediaPlayer is the maker's fork with its own ideas about retrying (see AerialStream).
 *
 * One thread does all the work: it opens the file, feeds the decoder and renders what comes out.
 */
class LocalFilmPlayer(
    /** How the picture fills the surface: SCALE_TO_FIT, or SCALE_TO_FIT_WITH_CROPPING. */
    private val scaling: Int = MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT,
) : FilmPlayer {
    /** One video in the playlist. */
    class Item(val id: String, val file: File)

    /** The first frame of a film has reached the screen. */
    var onFirstFrame: ((Item) -> Unit)? = null

    /** A new film is up: its item and where it is in the list. */
    var onItemChanged: ((Item, Int) -> Unit)? = null

    /** A film would not play: its item and what the platform said. */
    var onError: ((Item, String) -> Unit)? = null

    /** A film's picture size on screen, with any rotation taken into account. */
    var onSize: ((Item, Int, Int) -> Unit)? = null

    /** Runs [block] on the main thread, unless the player is already done with. */
    private fun tell(block: () -> Unit) {
        if (released) return
        main.post { if (!released) block() }
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()

    private var playlist = ArrayList<Item>()
    private var index = 0

    private var holder: SurfaceHolder? = null
    private var thread: Thread? = null

    @Volatile private var released = false
    @Volatile private var wantCodec = false
    @Volatile private var wantPlay = false
    @Volatile private var position = 0L
    @Volatile private var pendingSeek = -1L
    @Volatile private var pendingSkip = 0
    @Volatile private var pendingRestart = false
    @Volatile private var failures = 0

    /** The film that is up. */
    val currentItem: Item? get() = synchronized(lock) { playlist.getOrNull(index) }

    /** How many films are in the list. */
    val count: Int get() = synchronized(lock) { playlist.size }

    override val positionMs: Long get() = position

    override val currentId: String? get() = currentItem?.id

    override val isPlaying: Boolean get() = wantPlay && wantCodec

    override val isIdle: Boolean get() = !wantCodec

    override val droppedFrames: String? get() = null

    /** The films to play, in order. */
    fun setPlaylist(items: List<Item>) {
        synchronized(lock) {
            playlist = ArrayList(items)
            index = 0
            lock.notifyAll()
        }
    }

    /** Opens the decoder; the film moves when [play] is called. */
    override fun prepare() {
        synchronized(lock) {
            if (thread == null) {
                released = false
                thread = Thread({ run() }, "aerial-local-film").also { it.start() }
            }
            wantCodec = true
            lock.notifyAll()
        }
    }

    override fun play() {
        synchronized(lock) {
            wantPlay = true
            lock.notifyAll()
        }
    }

    override fun pause() {
        synchronized(lock) {
            wantPlay = false
            lock.notifyAll()
        }
    }

    /**
     * Lets the decoder go, keeping the film and its place: these TVs have one video path, and a
     * screensaver of its own needs it (Xiaomi MiTV-MFTR0, TCL MT5879: a second film failed to open
     * beside the wallpaper's). The film goes on from here when [goOn] is called.
     */
    override fun letGo() {
        synchronized(lock) {
            wantPlay = false
            wantCodec = false
            lock.notifyAll()
        }
    }

    /** After [letGo], when nothing else came to need it: the film goes on from where it was. */
    override fun goOn() {
        synchronized(lock) {
            wantCodec = true
            wantPlay = true
            lock.notifyAll()
        }
    }

    override fun release() {
        val t = synchronized(lock) {
            released = true
            wantPlay = false
            wantCodec = false
            lock.notifyAll()
            thread
        }
        thread = null
        runCatching { t?.join(800) }
        // Nothing left of ours to hold it: the last session's own close says so too, but a film let
        // go in the middle of opening leaves nothing behind to say it at all.
        FilmDecoder.drop(this)
    }

    override fun skip(step: Int) {
        synchronized(lock) {
            if (playlist.size <= 1) return
            pendingSkip = step
            lock.notifyAll()
        }
    }

    /**
     * Drops the film that is up: it would not play, and the list goes on without it. The player
     * opens the next one in its place.
     */
    fun removeCurrent() {
        synchronized(lock) {
            if (playlist.isEmpty()) return
            playlist.removeAt(index.coerceIn(0, playlist.lastIndex))
            if (index >= playlist.size) index = 0
            position = 0L
            failures = 0
            // The film that is up is gone: what is on the surface now is not the list's film, and
            // the decoder is holding a surface it should let go of.
            pendingRestart = true
            lock.notifyAll()
        }
    }

    /** Where the film should pick up, in milliseconds; a negative number keeps the place it is at. */
    fun seekTo(ms: Long) {
        synchronized(lock) {
            pendingSeek = ms
            lock.notifyAll()
        }
    }

    override fun setSurfaceView(view: SurfaceView?) {
        synchronized(lock) {
            holder = view?.holder
            lock.notifyAll()
        }
    }

    private fun surface(): Surface? {
        val h = holder ?: return null
        return runCatching { h.surface }.getOrNull()?.takeIf { it.isValid }
    }

    private fun run() {
        while (!released) {
            val item = synchronized(lock) {
                while (!released && !wantCodec) {
                    try { lock.wait(300) } catch (e: InterruptedException) { return }
                }
                if (released) return
                playlist.getOrNull(index)
            }
            if (item == null) {
                // The list is empty: nothing to play, and nothing to wake it.
                synchronized(lock) {
                    if (!released) try { lock.wait(300) } catch (e: InterruptedException) { return }
                }
                continue
            }

            // The surface comes a moment after the view does: wait for it rather than call it an
            // error, and rather than open a decoder on a surface that is not there yet.
            val surface = awaitSurface() ?: continue
            if (released) return

            val start = synchronized(lock) {
                val seek = pendingSeek
                pendingSeek = -1L
                if (seek >= 0) position = seek
                position
            }
            val session = open(item, surface, start)
            if (session == null) {
                failures++
                tell { onError?.invoke(item, "would not open") }
                // Never a give-up: the decoder can be held for a while by whoever needs it, and the
                // film is to come back on its own once the way is free.
                val wait = if (failures >= 3) 4_000L else 800L
                synchronized(lock) {
                    if (!released) try { lock.wait(wait) } catch (e: InterruptedException) { return }
                }
                continue
            }
            failures = 0
            // The film that is up is the one in the list now: a restart asked for while the last
            // one would not open is spent.
            pendingRestart = false
            tell {
                val at = synchronized(lock) { index }
                onItemChanged?.invoke(item, at)
            }
            val ended = decode(session)
            session.close()
            if (ended && !released) {
                // The film ran out: the next one takes over, and the list goes round again.
                position = 0L
                synchronized(lock) {
                    if (playlist.size > 1) index = (index + 1) % playlist.size
                    pendingSkip = 0
                }
            }
        }
    }

    /** The surface to draw on, once the view has one; null when the player is done. */
    private fun awaitSurface(): Surface? {
        while (!released && wantCodec) {
            surface()?.let { return it }
            synchronized(lock) {
                if (released || !wantCodec) return null
                try { lock.wait(200) } catch (e: InterruptedException) { return null }
            }
        }
        return null
    }

    /** What an open film holds while it plays. */
    private class Session(
        val extractor: MediaExtractor,
        val codec: MediaCodec,
        val surface: Surface,
        val item: Item,
        private val owner: LocalFilmPlayer,
    ) {
        @Volatile private var closed = false

        fun close() {
            if (closed) return
            closed = true
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
            // Said before the last line, so whoever is waiting for the way to be free sees it go
            // before its own turn comes round.
            FilmDecoder.drop(owner)
        }
    }

    private fun open(item: Item, surface: Surface, startMs: Long): Session? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(item.file.absolutePath)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    track = i
                    break
                }
            }
            if (track < 0) {
                extractor.release()
                return null
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
                extractor.release()
                return null
            }
            // The rotation is in the file's own header, not in the track format: the decoder is told
            // about it the way the platform's own player does, so the picture comes out upright.
            val rotation = rotationOf(item.file)
            if (rotation != 0) format.setInteger(KEY_ROTATION, rotation)
            // The picture's own size on screen, as ExoPlayer worked it out: the crop the file
            // asks for, and the rotation, which turns the picture on its side.
            var w = format.optInt(MediaFormat.KEY_WIDTH)
            var h = format.optInt(MediaFormat.KEY_HEIGHT)
            val right = format.optInt(KEY_CROP_RIGHT)
            val left = format.optInt(KEY_CROP_LEFT)
            val bottom = format.optInt(KEY_CROP_BOTTOM)
            val top = format.optInt(KEY_CROP_TOP)
            if (right > 0 && left > 0 && bottom > 0 && top > 0) {
                w = right - left + 1
                h = bottom - top + 1
            }
            val turned = rotation == 90 || rotation == 270
            val across = if (turned) h else w
            val upright = if (turned) w else h
            if (across > 0 && upright > 0) tell { onSize?.invoke(item, across, upright) }
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, surface, null, 0)
            runCatching { codec.setVideoScalingMode(scaling) }
            codec.start()
            if (startMs > 0) extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            // The decoder is this TV's only one: say it is taken, so a frame asked for elsewhere
            // (a film just uploaded wanting its picture) waits for us to let it go.
            FilmDecoder.hold(this@LocalFilmPlayer)
            return Session(extractor, codec, surface, item, this@LocalFilmPlayer)
        } catch (e: Exception) {
            DiagLog.i("local film ${item.id} would not open: ${e.javaClass.simpleName}")
            runCatching { extractor.release() }
            return null
        }
    }

    /**
     * Feeds the decoder and renders what comes out. True when the film ran to its end, false when
     * it was let go, paused or the player was released.
     */
    private fun decode(session: Session): Boolean {
        val extractor = session.extractor
        val codec = session.codec
        val info = MediaCodec.BufferInfo()
        var first = true
        var inputDone = false
        while (!released) {
            if (!wantCodec) return false
            if (!wantPlay) {
                // Hold the film on its frame: the decoder keeps the picture, the picture stays.
                synchronized(lock) {
                    if (released) return false
                    try { lock.wait(120) } catch (e: InterruptedException) { return false }
                }
                continue
            }
            val skip = pendingSkip
            if (skip != 0) {
                pendingSkip = 0
                synchronized(lock) {
                    if (playlist.size > 1) {
                        index = ((index + skip) % playlist.size + playlist.size) % playlist.size
                        position = 0L
                    }
                }
                return false
            }
            // A place to pick up from: the session is made again there, which is surer than
            // pushing a seek through a decoder that is already running.
            val seek = pendingSeek
            if (seek >= 0) {
                pendingSeek = -1L
                position = seek
                return false
            }
            if (pendingRestart) {
                pendingRestart = false
                position = 0L
                return false
            }
            // The view's surface can be made again at any time; the decoder holds the old one.
            if (surface() !== session.surface) return false
            if (!inputDone) {
                val indexIn = codec.dequeueInputBuffer(TIMEOUT_US)
                if (indexIn >= 0) {
                    val buffer = codec.getInputBuffer(indexIn)
                    val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(indexIn, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(indexIn, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val indexOut = codec.dequeueOutputBuffer(info, 0)
            if (indexOut >= 0) {
                val render = info.size > 0
                codec.releaseOutputBuffer(indexOut, render)
                if (render) {
                    position = info.presentationTimeUs / 1000
                    if (first) {
                        first = false
                        tell { onFirstFrame?.invoke(session.item) }
                    }
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    // One film on its own goes round again without the decoder being made afresh:
                    // a wallpaper's film is on for hours, and a new decoder each loop would cost
                    // the machine more than the picture is worth. The decoder is flushed and
                    // started again on the file's first sample, which is what the platform's own
                    // players do for a loop. Should a decoder take that badly, the session is
                    // made again instead, which costs a moment of black.
                    if (count <= 1) {
                        val again = runCatching {
                            codec.flush()
                            codec.start()
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        }.isSuccess
                        if (again) {
                            inputDone = false
                            position = 0L
                            continue
                        }
                    }
                    return true
                }
            }
        }
        return false
    }

    /** The picture's rotation, in degrees, as the file's own header has it. */
    private fun rotationOf(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun MediaFormat.optInt(key: String): Int = try {
        if (containsKey(key)) getInteger(key) else 0
    } catch (e: Exception) {
        0
    }

    companion object {
        /** How long the decoder is asked to wait for a buffer, in microseconds. */
        private const val TIMEOUT_US = 10_000L

        /** The rotation key, as the platform names it. */
        private const val KEY_ROTATION = "rotation-degrees"

        /** The crop keys, as the platform names them (MediaFormat.KEY_CROP_* needs API 21). */
        private const val KEY_CROP_RIGHT = "crop-right"
        private const val KEY_CROP_LEFT = "crop-left"
        private const val KEY_CROP_BOTTOM = "crop-bottom"
        private const val KEY_CROP_TOP = "crop-top"
    }
}
