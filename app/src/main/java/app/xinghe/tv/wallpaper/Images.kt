package app.xinghe.tv.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/** Bitmap helpers shared by the wallpaper code. All of it runs off the main thread. */
object Images {

    /** Decodes [file] no larger than needed for [w]×[h], then centre-crops it to exactly that size. */
    fun decodeCover(file: File, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val src = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return cover(src, w, h).also { if (it !== src) src.recycle() }
    }

    fun cover(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val scale = max(w / src.width.toFloat(), h / src.height.toFloat())
        val cw = (w / scale).roundToInt().coerceAtMost(src.width)
        val ch = (h / scale).roundToInt().coerceAtMost(src.height)
        val l = (src.width - cw) / 2
        val t = (src.height - ch) / 2
        val out = createBitmap(w, h)
        Canvas(out).drawBitmap(src, Rect(l, t, l + cw, t + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * Shading baked into a home backdrop so the clock and the top row read over any picture, the way
     * tvOS darkens the area under its app rows. Baked once, so the TV draws a single full-screen layer.
     * Light since 1.28: the grid has its own darkened frosted background and the top row its plate,
     * and the old shade (88% black at the bottom, 42% more on the left) made photos look dim (#157).
     */
    /** The home shading, top to bottom: black at these alphas at [SHADE_AT]; and [SHADE_LEFT] fading out by mid-screen. */
    val SHADE = floatArrayOf(0.12f, 0.04f, 0.22f, 0.5f)
    val SHADE_AT = floatArrayOf(0f, 0.3f, 0.66f, 1f)
    const val SHADE_LEFT = 0.2f

    fun shadeForHome(b: Bitmap): Bitmap {
        val out = if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, h,
                SHADE.map(::black).toIntArray(),
                SHADE_AT, Shader.TileMode.CLAMP,
            )
        })
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = LinearGradient(0f, 0f, w * 0.5f, 0f, black(SHADE_LEFT), 0, Shader.TileMode.CLAMP)
        })
        return out
    }

    /**
     * A home backdrop frosted: shrunk and blurred three times over. Small
     * on purpose; it is only ever seen blurred, and it is drawn stretched in a single pass.
     */
    fun frost(b: Bitmap): Bitmap {
        val w = 320
        val h = 180
        val small = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { boxBlur(px, w, h, 7) }
        val out = small.copy(Bitmap.Config.ARGB_8888, true).also { if (it !== small) small.recycle() }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Fine noise, so long gradients do not band on 8-bit panels. */
    fun dither(b: Bitmap) {
        val w = b.width
        val h = b.height
        val row = IntArray(w)
        val rnd = Random(7)
        for (y in 0 until h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val n = rnd.nextInt(-2, 3)
                val r = ((c shr 16) and 0xFF) + n
                val g = ((c shr 8) and 0xFF) + n
                val bl = (c and 0xFF) + n
                row[x] = (c and 0xFF000000.toInt()) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or bl.coerceIn(0, 255)
            }
            b.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        pass(px, tmp, w, h, r, horizontal = true)
        pass(tmp, px, w, h, r, horizontal = false)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        for (l in 0 until lines) {
            for (i in 0 until len) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val c = if (horizontal) src[l * w + j] else src[j * w + l]
                    a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                val idx = if (horizontal) l * w + i else i * w + l
                dst[idx] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }

    private fun black(alpha: Float) = android.graphics.Color.argb((alpha * 255).roundToInt(), 0, 0, 0)
}
