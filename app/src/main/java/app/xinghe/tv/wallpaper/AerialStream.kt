package app.xinghe.tv.wallpaper

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File

/**
 * Streams Apple's aerial films with Media3 ExoPlayer rather than the platform MediaPlayer, which on
 * TVs is the maker's own fork with its own network handling (TclNuPlayer on TCL 75S365C retried a
 * failing load 10 times over 34 s before reporting it, and every film started on a fresh connection).
 *
 * - One player per screensaver with all films as a playlist, so the next film loads while the current
 *   one ends and they follow each other without a black gap.
 * - Short network timeouts and a single retry: a slow edge of Apple's CDN costs seconds, not a minute.
 * - These files keep their index (moov) at the very end, so a cold start is two round trips to Apple
 *   (the index, then the start of the film) before the first frame. For the film that opens the next
 *   screensaver, the first seconds and the index are fetched ahead into a small disk cache; that film
 *   then starts from disk. Playback itself never writes to the cache.
 */
@OptIn(UnstableApi::class)
object AerialStream {
    private const val TAG = "AerialStream"

    /** About 3 s of film at Apple's 2K bitrate (15–20 Mbps): enough to start without the network. */
    private const val HEAD_BYTES = 8L * 1024 * 1024

    /** The index is 115–170 KB in these files; a generous tail covers it. */
    private const val TAIL_BYTES = 1L * 1024 * 1024

    /** Openers kept ready for the next screensavers; the cache never holds more than a few. */
    private const val OPENERS = 3
    private const val CACHE_BYTES = 48L * 1024 * 1024

    @Volatile
    private var cache: SimpleCache? = null

    /** One instance per process: SimpleCache locks its folder, and Home and the system screensaver share it. */
    private fun cache(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.applicationContext.cacheDir, "aerials"),
            LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }

    private fun http() = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(5_000)
        .setReadTimeoutMs(8_000)

    fun url(film: Aerial, quality: AerialQuality, overHttp: Boolean): String =
        film.url(quality).let { if (overHttp) it.replaceFirst("https://", "http://") else it }

    /** Keyed by film and quality, not address, so the cached opener serves HTTP and HTTPS alike. */
    private fun key(film: Aerial, quality: AerialQuality) = "${film.id}@${quality.key}"

    fun mediaItem(film: Aerial, quality: AerialQuality, overHttp: Boolean): MediaItem =
        MediaItem.Builder().setUri(url(film, quality, overHttp)).setMediaId(film.id).setCustomCacheKey(key(film, quality)).build()

    fun player(context: Context, quality: AerialQuality): ExoPlayer {
        val source = CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(http())
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // ExoPlayer's default video buffer is sized for phones (up to 128 MB); TVs here have 1–2 GB.
        // 24 MB is 10–13 s of 1080p film, which is also when the next film starts loading; the 4K
        // files get about as many seconds.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 1_000, 2_000)
            .setTargetBufferBytes(
                when (quality) {
                    AerialQuality.DolbyVision -> 48
                    AerialQuality.Uhd -> 32
                    else -> 24
                } * 1024 * 1024,
            )
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        return ExoPlayer.Builder(context)
            .setLoadControl(load)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(source).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1)),
            )
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ALL
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            }
    }

    private fun isReady(cache: SimpleCache, film: Aerial, quality: AerialQuality): Boolean {
        val key = key(film, quality)
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length > 0 && cache.isCached(key, 0, HEAD_BYTES) &&
            cache.isCached(key, length - TAIL_BYTES, TAIL_BYTES)
    }

    /** [films] with one of the films fetched ahead, if any, moved to the front. */
    fun withOpenerFirst(context: Context, films: List<Aerial>, quality: AerialQuality): List<Aerial> {
        val cache = cache(context)
        val opener = films.filter { isReady(cache, it, quality) }.randomOrNull() ?: return films
        return listOf(opener) + (films - opener)
    }

    /**
     * Fetches the start and the index of one more film for the next screensaver, unless [OPENERS]
     * are ready already. Runs while a film plays; about 9 MB.
     */
    suspend fun prepareOpener(context: Context, films: List<Aerial>, playing: Aerial, quality: AerialQuality, overHttp: Boolean) =
        runInterruptible(Dispatchers.IO) {
            val cache = cache(context)
            val candidates = films.filter { it != playing }
            if (candidates.count { isReady(cache, it, quality) } >= OPENERS) return@runInterruptible
            val film = candidates.filterNot { isReady(cache, it, quality) }.randomOrNull() ?: return@runInterruptible
            val key = key(film, quality)
            val started = SystemClock.elapsedRealtime()
            runCatching {
                val uri = android.net.Uri.parse(url(film, quality, overHttp))
                val length = contentLength(uri)
                ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, length) }
                    .let { cache.applyContentMetadataMutations(key, it) }
                val writer = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http())
                for ((position, size) in listOf(0L to HEAD_BYTES, length - TAIL_BYTES to TAIL_BYTES)) {
                    val spec = DataSpec.Builder().setUri(uri).setKey(key).setPosition(position).setLength(size).build()
                    CacheWriter(writer.createDataSource(), spec, null, null).cache()
                }
                Log.i(TAG, "next screensaver opens with $key, fetched in ${SystemClock.elapsedRealtime() - started} ms")
            }.onFailure { Log.w(TAG, "could not fetch ${film.id} ahead", it) }
        }

    /** The file's size, from the Content-Range of a one-byte request. */
    private fun contentLength(uri: android.net.Uri): Long {
        val source = http().createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(uri).setPosition(0).setLength(1).build())
            val range = source.responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value?.firstOrNull()
            return range?.substringAfterLast('/')?.toLongOrNull() ?: error("no Content-Range: $range")
        } finally {
            source.close()
        }
    }
}
