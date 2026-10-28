package app.xinghe.tv.wallpaper

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import app.xinghe.tv.BuildConfig
import app.xinghe.tv.guard.GuardSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import app.xinghe.tv.media.FilmDecoder
import app.xinghe.tv.system.DiagLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

enum class WallpaperKind { Daily, Builtin, Photo, Auto, Live, Video }

data class Wallpaper(
    val id: String,
    val kind: WallpaperKind,
    val title: String,
    /** Where the picture was taken, or who took it; shown in the screensaver. */
    val caption: String? = null,
)

/** System: none of Aerial's; the TV's own screensaver starts as the firmware has it (SystemScreensaver). */
enum class ScreensaverMode(val key: String) {
    Aerial("aerial"), Photos("photos"), Clock("clock"), Videos("videos"), System("system");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Aerial
    }
}

/**
 * Everything behind the home screen: which wallpaper is chosen, where pictures come from, and what
 * the screensaver shows. Sources:
 *
 * - Built-in: Aerial's own Mac-style pictures, drawn on the TV ([Gradients]).
 * - Daily: Bing's photo of the day (cn.bing.com, reachable from China without a proxy). The last
 *   eight are kept; "daily:latest" always follows the newest.
 * - Photos: pictures uploaded from a phone through the setup page ([PhoneServer]). "photos:rotate"
 *   shows a different one of them each day (issue #27).
 * - Auto: the previous behaviour, light taken from the focused app's artwork.
 * - Live: Apple's aerial films, moving once Home has sat still a while (ui/home/LiveWallpaper.kt).
 *   Its picture is the frame the film last stopped on, so the glass and the grid match it.
 * - Video: the owner's own uploaded films, one wallpaper each ("video:<id>"): the one picked plays
 *   on its own, no taking turns. Picked in the folder the video wallpaper's card opens.
 *
 * State is Compose snapshot state, written on the main thread only.
 */
object Wallpapers {
    private const val TAG = "AerialWallpaper"
    const val LATEST_DAILY = "daily:latest"
    private const val DAILY_RETRY_MS = 30 * 60 * 1000L
    /** Pictures up to this width are cards: the phone-made thumbnail serves them first. */
    private const val THUMB_W = 512
    /** What the pickers ask a picture at: the TV's own folder, and every film on the phone's page. */
    private const val CARD_W = 384
    private const val CARD_H = 216
    const val AUTO = "auto"
    const val ROTATE_PHOTOS = "photos:rotate"
    const val LIVE = "aerial:live"
    /** The owner's uploaded videos: one wallpaper each, "video:<id>", fixed — no taking turns. */
    const val VIDEO_PREFIX = "video:"
    fun videoWallpaperId(videoId: String) = VIDEO_PREFIX + videoId
    fun isVideoWallpaper(id: String) = id.startsWith(VIDEO_PREFIX)
    /** The wallpaper is one of the owner's uploaded videos. */
    val videoSelected: Boolean get() = isVideoWallpaper(selectedId)
    /** Which uploaded video the wallpaper is, or null when it is not one of them. */
    val selectedVideoId: String?
        get() = selectedId.removePrefix(VIDEO_PREFIX).takeIf { isVideoWallpaper(selectedId) && it.isNotEmpty() }
    private const val FOLLOW = "follow"
    private const val DEFAULT_FALLBACK = "grad:aurora"

    /** The built-ins Personalize offers, one or two of each family; the rest stay for whoever chose them. */
    val FEATURED_BUILTINS = setOf("grad:hills_dawn", "grad:hills_night", "grad:dunes_gold", "grad:sea_sunset", "grad:waves_blue", "grad:aurora")

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val io = Mutex()
    private val prefs by lazy { app.getSharedPreferences("wallpaper", Context.MODE_PRIVATE) }
    private val dailyDir by lazy { File(app.filesDir, "daily").apply { mkdirs() } }
    private val photoDir by lazy { File(app.filesDir, "photos").apply { mkdirs() } }
    private val liveFrame by lazy { File(app.filesDir, "live-frame.jpg") }
    /** The video wallpaper's stopped frame, kept apart from the aerial films' one (their cards and pictures differ). */
    private val videoFrame by lazy { File(app.filesDir, "video-frame.jpg") }
    /** The chosen videos' first frames, decoded once each and kept as JPEGs, by size. */
    private val firstFrameDir by lazy { File(app.filesDir, "video-frames").apply { mkdirs() } }
    /**
     * Whether the last try at the chosen video's own first frame came to nothing, so the gradient
     * standing in for it is all there is. The baked pair must not keep it past this start:
     * 1.5.3 baked the gradient for good where the frame would not decode.
     */
    @Volatile private var videoFrameFailed = false
    private val bakedDir by lazy { File(app.filesDir, "backdrop").apply { mkdirs() } }
    private val thumbs = HashMap<String, Bitmap>()

    var items by mutableStateOf<List<Wallpaper>>(emptyList()); private set
    var selectedId by mutableStateOf(LATEST_DAILY); private set
    /** The screensaver the owner chose; null while it follows the wallpaper ([screensaver]). */
    private var chosenScreensaver by mutableStateOf<ScreensaverMode?>(ScreensaverMode.Aerial)

    /**
     * The screensaver in effect. Until one is chosen it follows the wallpaper: aerial films with the
     * aerial wallpaper, the owner's videos with the video wallpaper, photos with a picture, starting
     * from that picture. "Follow App", having no picture, carries the aerial films on.
     */
    val screensaver: ScreensaverMode
        get() = chosenScreensaver ?: when {
            selectedId == LIVE || selectedId == AUTO -> ScreensaverMode.Aerial
            isVideoWallpaper(selectedId) -> ScreensaverMode.Videos
            else -> ScreensaverMode.Photos
        }

    /** No screensaver chosen: it follows the wallpaper. */
    val screensaverFollows: Boolean get() = chosenScreensaver == null

    /** The home backdrop for the selection, shading baked in; null while loading or in Auto. */
    var backdrop by mutableStateOf<ImageBitmap?>(null); private set

    /**
     * The same picture frosted: blurred small and darkened. The glass under the top row samples it,
     * and the whole screen turns to it once focus goes down into the grid, as on tvOS — except
     * behind the video wallpaper's film, which keeps playing there (ui/home/HomeChrome.kt).
     */
    var frosted by mutableStateOf<ImageBitmap?>(null); private set

    private var backdropJob: Job? = null
    private var started = false

    /** The UI's size in pixels. Pictures are decoded to it: 1080p on most TVs, 4K where the UI is. */
    var screenW = 1920; private set
    var screenH = 1080; private set

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        context.resources.displayMetrics.let {
            screenW = maxOf(it.widthPixels, it.heightPixels)
            screenH = minOf(it.widthPixels, it.heightPixels)
        }
        selectedId = (prefs.getString("selected", LATEST_DAILY) ?: LATEST_DAILY)
            // A built-in wallpaper from before the Mac-style set: its nearest one now.
            .let { if (it.startsWith("grad:")) "grad:" + Gradients.currentKey(it.removePrefix("grad:")) else it }
        chosenScreensaver = when (val key = prefs.getString("screensaver", null)) {
            null -> firstScreensaver()
            // "wallpaper" was a fourth kind in test builds of 1.38, for what following now is.
            FOLLOW, "wallpaper" -> null
            else -> ScreensaverMode.of(key)
        }
        rebuildList()
        loadBaked()
        rebuildBackdrop()
        scope.launch { refreshDaily() }
        scope.launch(Dispatchers.IO) { clearStaleUploads() }
        // Installs from before 1.42 have a baked picture but no copy yet.
        scope.launch(Dispatchers.IO) { keepForLockedBoot() }
        // Nothing is playing yet, so the decoder is free: the films that came in without a picture
        // of their own get one now, before the day's first film asks for the way.
        scope.launch(Dispatchers.IO) { catchUpFrames("start") }
    }

    /**
     * Uploads pass through temp files in the cache folder (ours, CustomIcons', and NanoHTTPD's for
     * each part), deleted when the upload ends. One cut off midway, or the process dying, leaves
     * its file behind, and those piled up (#193). Only those, and only an hour old: the aerial
     * cache beside them keeps itself under 48 MB.
     */
    private fun clearStaleUploads() {
        val before = System.currentTimeMillis() - 60 * 60 * 1000L
        app.cacheDir.listFiles { f ->
            f.isFile && f.lastModified() < before &&
                (f.name.startsWith("upload") || f.name.startsWith("icon") || f.name.startsWith("NanoHTTPD-"))
        }?.forEach { val kb = it.length() / 1024; if (it.delete()) Log.i(TAG, "removed a stale upload, $kb KB") }
    }

    fun select(id: String) {
        selectedId = id
        prefs.edit { putString("selected", id) }
        rebuildBackdrop()
    }

    /**
     * A new install's screensaver follows the wallpaper. One that was already in use when that came
     * (1.38) keeps what it showed, aerial films, until the owner chooses. Kept either way, so it
     * does not change with a later update.
     */
    private fun firstScreensaver(): ScreensaverMode? {
        val fresh = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).let { it.firstInstallTime == it.lastUpdateTime }
        }.getOrDefault(false)
        prefs.edit { putString("screensaver", if (fresh) FOLLOW else ScreensaverMode.Aerial.key) }
        return if (fresh) null else ScreensaverMode.Aerial
    }

    /** The wallpaper's picture, when the photo screensaver starts from it: a picture wallpaper, not the aerial one. */
    fun screensaverPicture(): Wallpaper? =
        if (screensaver != ScreensaverMode.Photos || selectedId == LIVE || selectedId == AUTO) null else resolve()

    fun chooseScreensaver(mode: ScreensaverMode) {
        chosenScreensaver = mode
        prefs.edit { putString("screensaver", mode.key) }
        GuardSetup.screensaverModeChanged(app)
    }

    /** The concrete wallpaper the selection points at today. */
    fun resolve(id: String = selectedId): Wallpaper? = when (id) {
        LATEST_DAILY -> items.firstOrNull { it.kind == WallpaperKind.Daily }
        ROTATE_PHOTOS -> items.filter { it.kind == WallpaperKind.Photo }.sortedBy { it.id }.let { photos ->
            if (photos.isEmpty()) resolve(LATEST_DAILY) else photos[Math.floorMod(today(), photos.size)]
        }
        else -> items.firstOrNull { it.id == id }
    }

    private fun today(): Long = java.time.LocalDate.now().toEpochDay()

    /** The day the rotating photo was last picked for; a new day picks the next one. */
    private var rotatedOn = 0L

    /** Moves "My Photos, rotating" on to today's photo; Aerial calls it whenever Home comes back. */
    fun rotateIfDue() {
        if (!started || selectedId != ROTATE_PHOTOS || rotatedOn == today()) return
        rebuildBackdrop()
    }

    // ---- pictures ----

    private fun fileFor(w: Wallpaper): File? = when (w.kind) {
        WallpaperKind.Daily -> File(dailyDir, w.id.removePrefix("daily:") + ".jpg")
        WallpaperKind.Photo -> File(photoDir, w.id.removePrefix("photo:") + ".jpg")
        else -> null
    }

    /** Full picture at [w]×[h] without shading, for the photo screensaver. Call off the main thread. */
    fun picture(wp: Wallpaper, w: Int, h: Int): Bitmap? = when (wp.kind) {
        WallpaperKind.Builtin -> Gradients.byKey(wp.id.removePrefix("grad:"))?.let { Gradients.render(it, w, h) }
        WallpaperKind.Daily, WallpaperKind.Photo -> fileFor(wp)?.takeIf { it.exists() }?.let { Images.decodeCover(it, w, h) }
        WallpaperKind.Auto -> null
        // Until a film has stopped on a frame, the first built-in stands in.
        WallpaperKind.Live -> liveFrame.takeIf { it.exists() }?.let { Images.decodeCover(it, w, h) }
            ?: Gradients.render(Gradients.presets.first(), w, h)
        // The video wallpaper's picture is the frame it last stopped on, else the chosen video's
        // own first frame.
        WallpaperKind.Video -> videoPicture(wp.id, w, h)
    }

    /**
     * The picture of one of the owner's videos: the frame it last stopped on (when the film on
     * screen came from this very video), else its first frame. The gradient stands in only when
     * there is no video to have a frame; when the video is there but its frame would not decode,
     * [videoFrameFailed] says so, so the gradient is not mistaken for the video's picture.
     *
     * [standIn] is off for the pickers' thumbnails: there a gradient would read as a picture of
     * its own — the card would look like a wallpaper of Aerial's, and once cached, keep it for
     * good — where an empty card with the film's name at least says which film it is.
     */
    private fun videoPicture(id: String, w: Int, h: Int, standIn: Boolean = true): Bitmap? {
        val videoId = id.removePrefix(VIDEO_PREFIX)
        // Only the chosen wallpaper's frame matters here: the sheet asks for every video's
        // thumbnail, and one of those failing says nothing about the one on screen.
        val chosen = id == selectedId
        if (liveSpot()?.first?.removePrefix("local:") == videoId) {
            if (chosen) videoFrameFailed = false
            videoFrame.takeIf { it.exists() }?.let { Images.decodeCover(it, w, h)?.takeUnless(Images::blank) }?.let { return it }
        }
        val video = ScreensaverVideos.all(app).firstOrNull { it.id == videoId }
        if (video == null) {
            // The video went: there is nothing to decode, and nothing to wait for.
            if (chosen) videoFrameFailed = false
            return Gradients.render(Gradients.presets.first(), w, h).takeIf { standIn }
        }
        // The thumbnail the phone's browser made when the film was uploaded is the one picture of
        // it this TV can always show: that browser decodes what the TV's retriever will not, and
        // no second decoder is opened here to try — these TVs have one, and the wallpaper's player
        // holds it. Cards serve it first; for the full-screen backdrop the TV's own first frame
        // comes first, the thumbnail enlarged standing in where that will not decode.
        val thumb = thumbOf(video)
        val frame = if (w <= THUMB_W) {
            thumb?.let { Images.decodeCover(it, w, h)?.takeUnless(Images::blank) } ?: videoFirstFrame(video, w, h)
        } else {
            videoFirstFrame(video, w, h) ?: thumb?.let { Images.decodeCover(it, w, h)?.takeUnless(Images::blank) }
        }
        if (chosen) videoFrameFailed = frame == null
        return frame ?: Gradients.render(Gradients.presets.first(), w, h).takeIf { standIn }
    }

    /** The picture of [video] the phone's browser made as it was uploaded, when there is one. */
    private fun thumbOf(video: ScreensaverVideo): File? =
        File(video.file.parentFile, "thumb.jpg").takeIf { it.isFile && it.length() > 0 }

    /**
     * The first frame of one of the owner's videos, for its picture and its thumbnail. Decoded
     * once and kept as a JPEG under the video's own date, per size asked for: the wallpaper
     * sheet asks for every video's thumbnail and Home for the chosen one's picture, on every
     * start, and decoding is the slow part.
     *
     * The decoding is tried every way a device has been seen to allow it. The scaled call (API
     * 27) is the one that should do it, and the one that fails on the most devices — a video
     * with rotation metadata, an awkward size, or time 0 itself, and it answers null. Some
     * devices read the path but not a frame from it, where the file descriptor works. 1.5.3
     * took the first null for the whole answer, and showed a gradient where the video's own
     * first frame should be (TCL 65S515D).
     */
    private fun videoFirstFrame(video: ScreensaverVideo, w: Int, h: Int): Bitmap? {
        val cache = frameCache(video, w, h)
        if (cache.exists()) BitmapFactory.decodeFile(cache.path)?.let { return it }
        val opening = decodeFirstFrame(video.file, w, h) ?: return null
        // Plenty of films open on a black frame and fade in: a card with a black picture on it
        // looks no different from a card with nothing to show.
        val frame = if (Images.blank(opening)) laterFrame(video.file, w, h)?.also { opening.recycle() } ?: opening else opening
        runCatching {
            cache.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            // The same video's other sizes, or an older cut of it, are no longer of use.
            firstFrameDir.listFiles()?.forEach { f ->
                if (f != cache && f.name.startsWith("first-${video.id}-")) f.delete()
            }
        }.onFailure { Log.w(TAG, "could not keep a video's first frame", it) }
        return frame
    }

    /**
     * A frame from further into the film, for one whose first frame is nothing to look at. The
     * phone's page takes the same trouble with the picture it sends along.
     */
    private fun laterFrame(file: File, w: Int, h: Int): Bitmap? {
        val path = file.absolutePath
        val length = retrieve(path) { it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }
            ?.takeIf { it > 2_000 } ?: return null
        for (ms in listOf(length / 10, length / 2)) {
            val frame = retrieve(path) { it.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }
                ?: retrieve(path) { it.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST) }
            if (frame != null && !Images.blank(frame)) return sized(frame, w, h)
        }
        return null
    }

    /** Where a film's first frame at a size is kept, once it has been decoded once. */
    private fun frameCache(video: ScreensaverVideo, w: Int, h: Int) =
        File(firstFrameDir, "first-${video.id}-${video.file.lastModified()}-${w}x$h.jpg")

    /**
     * A picture of its own for every uploaded film, made here rather than waited for.
     *
     * The phone sends a frame of the film along with it, but its browser cannot decode every film
     * this TV plays, and plenty arrive without one. Until a film has been played as the wallpaper,
     * what is on file is all its card has to show — hence empty cards for a batch's second film
     * and later, whose thumbnail had not come and whose frame this TV would not read while the
     * wallpaper's player held its one decoder.
     *
     * Call off the main thread. It touches the decoder only when the way is free, or borrows it
     * from the film on Home for the moment: a picture is not worth stopping a film that is playing.
     * True when every film has one now.
     */
    fun catchUpFrames(reason: String): Boolean {
        if (!started) return true
        val missing = ScreensaverVideos.all(app).filter { !frameCache(it, CARD_W, CARD_H).exists() }
        if (missing.isEmpty()) return true
        if (FilmDecoder.held && borrowDecoder == null) {
            DiagLog.i("video frames wait ($reason): ${missing.size} without a picture, the decoder is held")
            return false
        }
        var made = 0
        for (video in missing) {
            val at = SystemClock.elapsedRealtime()
            var frame: Bitmap? = null
            val lender = borrowDecoder
            // Borrowed rather than taken: a second decoder opened beside the film on Home stops
            // that film where it stands (1.5.5), which a picture for a card does not pay for.
            if (FilmDecoder.held) lender?.invoke { frame = videoFirstFrame(video, CARD_W, CARD_H) }
            else frame = videoFirstFrame(video, CARD_W, CARD_H)
            val takes = SystemClock.elapsedRealtime() - at
            if (frame != null) made++
            DiagLog.i("video frame ${video.name}: ${if (frame != null) "made in $takes ms" else "would not decode ($takes ms)"} ($reason)")
            if (FilmDecoder.held && borrowDecoder == null) break
        }
        DiagLog.i("video frames: $made of ${missing.size} made ($reason)")
        return missing.all { frameCache(it, CARD_W, CARD_H).exists() }
    }

    private var framesJob: Job? = null

    /**
     * [catchUpFrames] over the next few moments rather than at once: an upload has just been
     * served, and a film playing behind Home keeps its decoder until it lets it go of its own
     * accord. Nothing presses. What is still missing waits for the next start.
     */
    fun requestFrames(reason: String) {
        if (!started) return
        scope.launch {
            framesJob?.cancel()
            framesJob = scope.launch(Dispatchers.IO) {
                repeat(4) { round ->
                    if (catchUpFrames(reason)) return@launch
                    delay(if (round == 0) 500L else 4_000L)
                }
            }
        }
    }

    /**
     * Set by Home's film: runs [body] — off the main thread — with the decoder let go for the
     * moment, then asks for it back. A first frame takes a few hundred milliseconds, and the film
     * picks up where it was left.
     */
    var borrowDecoder: ((body: () -> Unit) -> Unit)? = null

    /** Every way of asking for the first frame, until one answers. */
    private fun decodeFirstFrame(file: File, w: Int, h: Int): Bitmap? {
        val path = file.absolutePath
        if (Build.VERSION.SDK_INT >= 27) {
            // Scaled straight to the size asked for: one decode, no second bitmap.
            retrieve(path) { it.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h) }
                ?.let { return sized(it, w, h) }
        }
        retrieve(path) { it.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }
            ?.let { return sized(it, w, h) }
        retrieve(path) { it.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST) }
            ?.let { return sized(it, w, h) }
        retrieve(path) { it.getFrameAtTime() }?.let { return sized(it, w, h) }
        // Time 0 is itself what some devices answer null to: a hair later is the same frame.
        for (us in longArrayOf(1L, 1_000L, 250_000L)) {
            retrieve(path) { it.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }
                ?.let { return sized(it, w, h) }
            retrieve(path) { it.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST) }
                ?.let { return sized(it, w, h) }
        }
        // The path is not always a data source a frame can be read from: the descriptor can be.
        val stream = runCatching { FileInputStream(file) }.getOrNull() ?: return null
        stream.use {
            retrieve(it.fd) { mmr -> mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }
                ?.let { frame -> return sized(frame, w, h) }
        }
        // No codec of our own here, though the film plays: these TVs have one video decoder, and
        // a second one opened beside the wallpaper's stops that wallpaper where it stands (the
        // film froze and never came back, 1.5.5). The phone's thumbnail is the frame of record.
        return null
    }

    /** Opens a retriever on [path] and asks it for a frame; null when this way does not work here. */
    private fun <T> retrieve(path: String, body: (MediaMetadataRetriever) -> T?): T? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            body(mmr)
        } catch (e: Exception) {
            Log.w(TAG, "could not read a video frame by path", e)
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** The same over a file descriptor. */
    private fun <T> retrieve(fd: FileDescriptor, body: (MediaMetadataRetriever) -> T?): T? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(fd)
            body(mmr)
        } catch (e: Exception) {
            Log.w(TAG, "could not read a video frame by descriptor", e)
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** The frame at [w]×[h], in the config the rest of the wallpaper code expects. */
    private fun sized(frame: Bitmap, w: Int, h: Int): Bitmap {
        val scaled = if (frame.width == w && frame.height == h) frame
        else Bitmap.createScaledBitmap(frame, w, h, true)
        if (scaled !== frame && !frame.isRecycled) frame.recycle()
        return if (scaled.config == Bitmap.Config.ARGB_8888) scaled
        else scaled.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** A 16:9 thumbnail for pickers and the phone page. Call off the main thread. */
    fun thumbnail(id: String): Bitmap? {
        synchronized(thumbs) { thumbs[id] }?.let { return it }
        val wp = (if (id == LATEST_DAILY || id == ROTATE_PHOTOS) resolve(id) else items.firstOrNull { it.id == id }) ?: return null
        val b = when (wp.kind) {
            WallpaperKind.Auto -> Gradients.render(Gradients.autoThumb, 384, 216)
            // A video whose own frame will not decode is left without a picture here, rather than
            // given a gradient: the pickers show which film it is by its name, and a stand-in
            // cached once would keep the card from ever showing the frame that came later.
            WallpaperKind.Video -> videoPicture(wp.id, CARD_W, CARD_H, standIn = false)
            else -> picture(wp, 384, 216)
        }
        if (b != null) synchronized(thumbs) { thumbs[id] = b }
        return b
    }

    private fun rebuildBackdrop() {
        backdropJob?.cancel()
        val id = selectedId
        backdropJob = scope.launch {
            if (id == AUTO) {
                backdrop = null
                frosted = null
                return@launch
            }
            rotatedOn = today()
            val wp = resolve(id) ?: resolve(DEFAULT_FALLBACK)
            val key = wp?.let(::bakedKey)
            if (key != null && key == bakedKey && backdrop != null) return@launch
            val (bmp, frost) = withContext(Dispatchers.Default) {
                val picture = wp?.let { picture(it, screenW, screenH) }
                // Frosted from the picture before the home shading: the glass sits low on the
                // screen, where the shading is darkest, and would read as a grey slab.
                val frost = picture?.let(Images::frost)
                picture?.let(Images::shadeForHome) to frost
            }
            backdrop = bmp?.asImageBitmap()
            frosted = frost?.asImageBitmap()
            // A gradient standing in for a video whose first frame would not decode is not
            // baked: it would outlive the decoder's bad day, its key unchanged (1.5.3 showed
            // the gradient for good once it had been baked). The next start tries again.
            if (wp?.kind == WallpaperKind.Video && videoFrameFailed) {
                bakedKey = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { bakedDir.listFiles()?.forEach { it.delete() } }
                            .onFailure { Log.w(TAG, "could not clear the baked backdrop", it) }
                    }
                }
            } else if (key != null && bmp != null && frost != null) saveBaked(key, bmp, frost)
        }
    }

    // ---- the baked backdrop, kept for the next start ----

    /**
     * What the backdrop on disk was baked from; null when there is none. Baking takes a cold start
     * about two seconds on Xiaomi MiTV-MFTR0 (decoding, or drawing a built-in picture pixel by pixel,
     * then shading and frosting), and Home showed black, then the focused app's light, meanwhile
     * (issues #277, #294). Reading the baked pair back takes tens of milliseconds.
     */
    private var bakedKey: String? = null

    /** Changes with the picture behind [wp], the screen size, and Aerial's version (the baking may change). */
    private fun bakedKey(wp: Wallpaper): String {
        val stamp = when (wp.kind) {
            WallpaperKind.Daily, WallpaperKind.Photo -> fileFor(wp)?.lastModified() ?: 0L
            WallpaperKind.Live -> liveFrame.lastModified()
            // The stopped frame's file, and the videos folder's mtime (the first-frame picture).
            WallpaperKind.Video -> maxOf(videoFrame.lastModified(), File(app.filesDir, "screensaver-videos").lastModified())
            else -> 0L
        }
        return "${wp.id}|$stamp|${screenW}x$screenH|${BuildConfig.VERSION_CODE}"
    }

    /**
     * Puts the last baked backdrop up at once, on the main thread, so Home's first frame has it. Even
     * one that is out of date (a new day's photo): it is the picture Home last showed, and the fresh
     * one crossfades in once baked. Not in "Follow App", which has no backdrop.
     */
    private fun loadBaked() {
        if (selectedId == AUTO) return
        runCatching {
            val key = File(bakedDir, "key").takeIf { it.exists() }?.readText() ?: return
            val home = BitmapFactory.decodeFile(File(bakedDir, "home.jpg").path) ?: return
            val frost = BitmapFactory.decodeFile(File(bakedDir, "frost.png").path) ?: return
            if (home.width != screenW || home.height != screenH) return
            backdrop = home.asImageBitmap()
            frosted = frost.asImageBitmap()
            bakedKey = key
        }.onFailure { Log.w(TAG, "could not read the baked backdrop", it) }
    }

    private suspend fun saveBaked(key: String, home: Bitmap, frost: Bitmap) {
        bakedKey = key
        withContext(Dispatchers.IO) {
            runCatching {
                // The key last, so a write cut short is never taken for a good one.
                File(bakedDir, "key").delete()
                File(bakedDir, "home.jpg").outputStream().use { home.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                File(bakedDir, "frost.png").outputStream().use { frost.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(bakedDir, "key").writeText(key)
            }.onFailure { Log.w(TAG, "could not keep the baked backdrop", it) }
            keepForLockedBoot()
        }
    }

    /**
     * Before the user is unlocked at power-on, Home can read only device-protected storage, so the
     * baked picture is copied there and MainActivity shows it until the rest can be read. Only
     * that picture: settings, the parental PIN, photos and icons stay in credential storage. Not
     * in "Follow App", which has no picture of its own.
     */
    fun lockedBootBackdrop(context: Context): File? =
        if (Build.VERSION.SDK_INT >= 24) File(context.createDeviceProtectedStorageContext().filesDir, "boot-backdrop.jpg") else null

    private fun keepForLockedBoot() {
        val copy = lockedBootBackdrop(app) ?: return
        runCatching {
            val home = File(bakedDir, "home.jpg")
            if (selectedId == AUTO || !home.exists()) { copy.delete(); return }
            if (copy.exists() && copy.lastModified() >= home.lastModified()) return
            val part = File(copy.parentFile, copy.name + ".part")
            home.copyTo(part, overwrite = true)
            check(part.renameTo(copy))
        }.onFailure { Log.w(TAG, "could not keep the backdrop for a locked boot", it) }
    }

    /**
     * The frame a moving wallpaper film stopped on becomes the wallpaper's picture: shaded, frosted
     * and kept for the next start. Returns once Home has it. [frame] is screen-sized and is used up.
     * It was [atMs] into [film], where the film picks up when it next moves ([liveSpot]).
     */
    suspend fun useLiveFrame(frame: Bitmap, film: String, atMs: Long) {
        if (selectedId != LIVE && !videoSelected) return
        backdropJob?.cancel()
        prefs.edit { putString("live_film", film).putLong("live_at", atMs) }
        // Each kind keeps its stopped frame in its own file: the cards' thumbnails read them.
        val frameFile = if (videoSelected) videoFrame else liveFrame
        val (bmp, frost) = withContext(Dispatchers.Default) {
            runCatching { frameFile.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
                .onFailure { Log.w(TAG, "could not keep the film's frame", it) }
            // Frosted before shading, which draws on the frame itself.
            val frost = Images.frost(frame)
            Images.shadeForHome(frame) to frost
        }
        synchronized(thumbs) { thumbs.remove(LIVE); thumbs.remove(selectedId) }
        backdrop = bmp.asImageBitmap()
        frosted = frost.asImageBitmap()
        resolve(selectedId)?.let { saveBaked(bakedKey(it), bmp, frost) }
    }

    /**
     * The film and moment the wallpaper's picture was taken from, so the film starts again on the
     * picture on screen rather than cutting to another place. Null until one has stopped on a frame.
     */
    fun liveSpot(): Pair<String, Long>? {
        val film = prefs.getString("live_film", null)?.takeIf { it.isNotEmpty() } ?: return null
        return film to prefs.getLong("live_at", 0L)
    }

    /**
     * The glass for a moving wallpaper film that has just stopped, from a small frame near it: the
     * plate can stand over the paused film at once, before the exact frame is decoded for
     * [useLiveFrame]. [frame] is used up.
     */
    suspend fun useLiveFrost(frame: Bitmap) {
        if (selectedId != LIVE && !videoSelected) return
        backdropJob?.cancel()
        frosted = withContext(Dispatchers.Default) { Images.frost(frame) }.asImageBitmap()
    }

    /** Puts back the glass from before [useLiveFrost] when the exact frame could not be had. */
    fun restoreFrost(before: ImageBitmap?) {
        if ((selectedId == LIVE || videoSelected) && before != null) frosted = before
    }

    private fun rebuildList() {
        val daily = readDailyIndex().map { Wallpaper("daily:${it.first}", WallpaperKind.Daily, it.second, it.third) }
        val zh = Locale.getDefault().language == "zh"
        val builtins = Gradients.presets.map { Wallpaper("grad:${it.key}", WallpaperKind.Builtin, if (zh) it.nameZh else it.nameEn) }
        val photos = (photoDir.listFiles { f -> f.extension == "jpg" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { Wallpaper("photo:${it.nameWithoutExtension}", WallpaperKind.Photo, if (zh) "我的照片" else "My Photo") }
        val auto = Wallpaper(AUTO, WallpaperKind.Auto, if (zh) "跟随 App" else "Follow App")
        val live = Wallpaper(LIVE, WallpaperKind.Live, if (zh) "航拍" else "Aerial")
        // One wallpaper per uploaded video, each titled with the video's name: the folder the
        // video wallpaper's card opens lists them, and picking one fixes it. They exist only
        // while there is something to play; the list is rebuilt when videos change (refreshVideos).
        val videos = ScreensaverVideos.all(app)
            .map { Wallpaper(videoWallpaperId(it.id), WallpaperKind.Video, it.name) }
        items = listOfNotNull(live) + videos + daily + builtins + photos + auto
    }

    /**
     * Rescans the uploaded videos and rebuilds the list when they have changed: called at start and
     * from the phone page after a video is added or deleted, so the card appears (or goes) at once.
     */
    fun refreshVideos() {
        scanVideos()
    }

    /**
     * The same rescan, waited out.
     *
     * The phone's page asks for each film's picture the moment the upload answers, and a card the
     * list does not hold yet has no picture to give: `thumbnail` answers there is no such
     * wallpaper, the picture comes back empty, and the film shows as a blank tile until the page
     * is opened again. Called from the phone server, which may take its time here, never from the
     * UI thread.
     */
    fun refreshVideosSync() {
        runCatching { runBlocking { scanVideos()?.join() } }
            .onFailure { Log.w(TAG, "the video rescan did not finish", it) }
    }

    private fun scanVideos(): Job? {
        if (!started) return null
        return scope.launch(Dispatchers.IO) {
            val videos = ScreensaverVideos.all(app)
            // withContext, not launch: the rescan is not over until the list has been rebuilt, and
            // a caller waiting on the job's end waits for nothing otherwise.
            withContext(Dispatchers.Main) {
                // Every film on its own, not just whether there are any: a second or third film
                // uploaded while one was already listed never reached the folder (the list was
                // only rebuilt when the folder went from empty to not, or the other way).
                val listed = items.filter { it.kind == WallpaperKind.Video }.map { it.id }.toSet()
                val onHand = videos.map { videoWallpaperId(it.id) }.toSet()
                val chosenGone = videoSelected && videos.none { videoWallpaperId(it.id) == selectedId }
                if (listed != onHand || chosenGone) {
                    rebuildList()
                    if (chosenGone) {
                        // The video that was chosen went: the first of those left, or the daily photo.
                        select(videos.firstOrNull()?.let { videoWallpaperId(it.id) } ?: LATEST_DAILY)
                    } else if (videoSelected) rebuildBackdrop()
                } else if (videoSelected) {
                    // The set changed without the card going: the baked picture may be stale.
                    rebuildBackdrop()
                }
            }
            // A film that came in without a picture of its own: try for it here, where nothing is
            // playing yet if nothing is playing. What the film on Home keeps hold of waits its turn
            // (requestFrames, and the next start).
            catchUpFrames("rescan")
        }
    }

    // ---- daily photos ----

    private fun readDailyIndex(): List<Triple<String, String, String>> = runCatching {
        val arr = JSONArray(File(dailyDir, "index.json").readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Triple(o.getString("date"), o.getString("title"), o.getString("caption"))
        }.filter { File(dailyDir, it.first + ".jpg").exists() }
    }.getOrDefault(emptyList())

    private var lastDailyRefresh = 0L
    private var dailyFailed = false

    /**
     * Bing publishes one photo a day, at midnight in China; checking a few times a day is plenty,
     * plus once the day has turned (the date-change broadcast, or the first look after it), retried
     * every half hour until the new photo is out: a TV left on Home overnight kept yesterday's
     * photo for up to six hours (issue #286). A check that failed, usually because the TV was on
     * before its network was, is retried as soon as the network connects ([networkCameUp]), or
     * else a few minutes later (issue #129).
     */
    fun refreshDailyIfStale(networkCameUp: Boolean = false) {
        if (!started) return
        val since = System.currentTimeMillis() - lastDailyRefresh
        val due = since >= 6 * 60 * 60 * 1000L ||
            (dailyFailed && (networkCameUp || since >= 10 * 60 * 1000L)) ||
            (!hasTodaysDaily() && since >= DAILY_RETRY_MS)
        if (due) scope.launch { refreshDaily() }
    }

    /** Bing's dates are China's, as yyyyMMdd. */
    private fun hasTodaysDaily(): Boolean {
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
            .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        return readDailyIndex().any { it.first >= today }
    }

    private var dailyRetry: Job? = null

    /** Looks again in half an hour while today's photo is not out; Home may stay up all night. */
    private fun retryDailyLater() {
        dailyRetry?.cancel()
        if (hasTodaysDaily()) return
        dailyRetry = scope.launch {
            kotlinx.coroutines.delay(DAILY_RETRY_MS)
            refreshDailyIfStale()
        }
    }

    suspend fun refreshDaily() = io.withLock {
        lastDailyRefresh = System.currentTimeMillis()
        val added = withContext(Dispatchers.IO) {
            runCatching {
                // The market sets the language of the captions; Bing has no Russian or Ukrainian ones
                // (it answers those markets in English), so everything but Chinese asks for English.
                val market = if (Locale.getDefault().language == "zh") "zh-CN" else "en-US"
                // Each market has its own photo for a given date, and the files are named by date.
                val marketFile = File(dailyDir, "market")
                // No file: kept before markets were told apart, which was always zh-CN.
                val before = marketFile.takeIf { it.exists() }?.readText() ?: "zh-CN"
                val json = JSONObject(get("https://cn.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=$market").decodeToString())
                // Only once the new market's list is here: offline, the photos on hand stay.
                if (before != market) dailyDir.listFiles { f -> f.extension == "jpg" }?.forEach { it.delete() }
                if (before != market || !marketFile.exists()) marketFile.writeText(market)
                val images = json.getJSONArray("images")
                val index = JSONArray()
                for (i in 0 until images.length()) {
                    val img = images.getJSONObject(i)
                    val date = img.getString("startdate")
                    val file = File(dailyDir, "$date.jpg")
                    if (!file.exists()) {
                        // url is the 1920×1080 picture; a UI drawn above 1080p gets the UHD one.
                        val path = if (screenW > 1920) img.getString("urlbase") + "_UHD.jpg" else img.getString("url")
                        val bytes = get("https://cn.bing.com" + path)
                        file.writeBytes(bytes)
                    }
                    val copyright = img.optString("copyright")
                    val title = img.optString("title").ifBlank { copyright.substringBefore(" (").ifBlank { date } }
                    index.put(JSONObject().put("date", date).put("title", title).put("caption", copyright))
                }
                File(dailyDir, "index.json").writeText(index.toString())
                // Keep only what Bing still lists.
                val keep = (0 until index.length()).map { index.getJSONObject(it).getString("date") + ".jpg" }.toSet()
                dailyDir.listFiles { f -> f.extension == "jpg" && f.name !in keep }?.forEach { it.delete() }
                true
            }.onFailure { Log.w(TAG, "daily photos unavailable", it) }.isSuccess.also { dailyFailed = !it }
        }
        retryDailyLater()
        if (added) {
            synchronized(thumbs) { thumbs.remove(LATEST_DAILY) }
            rebuildList()
            if (selectedId.startsWith("daily:")) rebuildBackdrop()
        }
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        return c.inputStream.use { it.readBytes() }.also { c.disconnect() }
    }

    // ---- uploads ----

    /** Stores an uploaded picture, downscaled to at most 2560 px wide, and returns its id. */
    suspend fun addPhoto(input: InputStream): String? = io.withLock {
        val id = withContext(Dispatchers.IO) {
            val tmp = File.createTempFile("upload", ".bin", app.cacheDir)
            try {
                tmp.outputStream().use { input.copyTo(it) }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tmp.path, bounds)
                if (bounds.outWidth <= 0) return@withContext null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 2560) sample *= 2
                val bmp = BitmapFactory.decodeFile(tmp.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
                val name = System.currentTimeMillis().toString(36)
                File(photoDir, "$name.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bmp.recycle()
                "photo:$name"
            } finally {
                tmp.delete()
            }
        }
        if (id != null) {
            synchronized(thumbs) { thumbs.remove(ROTATE_PHOTOS) }
            rebuildList()
            select(id)
        }
        id
    }

    fun deletePhoto(id: String) {
        // Only photos that are listed: the id comes from the phone page, and "photo:../daily/..."
        // would otherwise reach other files (#57).
        val photo = items.firstOrNull { it.id == id && it.kind == WallpaperKind.Photo } ?: return
        fileFor(photo)?.delete()
        synchronized(thumbs) { thumbs.remove(id); thumbs.remove(ROTATE_PHOTOS) }
        rebuildList()
        if (selectedId == id) select(LATEST_DAILY)
        else if (selectedId == ROTATE_PHOTOS) rebuildBackdrop()
    }
}
